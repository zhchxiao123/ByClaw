package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.CubeSandboxClient.CubeException;
import com.iwhalecloud.byai.gateway.direct.CubeSandboxClient.CubeSandbox;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeBindingMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

import lombok.extern.slf4j.Slf4j;

/**
 * Creates or resumes the one private Cube runtime of an owner scope.
 * <p>
 * Concurrent ensures for the same owner coalesce in-process and are serialized across backend
 * replicas by an owner lock. A create intent is persisted before CubeAPI is called; an unknown
 * create result is reconciled by intent metadata instead of creating a second instance.
 * Ready means the runtime answered a signed status probe with the expected protocol, owner and
 * generation, not merely that an address exists.
 */
@Slf4j
public class RuntimeBindingService {

    public static final String PROVIDER = "cube";
    public static final String PROVISIONING = "provisioning";
    public static final String STARTING = "starting";
    public static final String READY = "ready";
    public static final String PAUSED = "paused";
    public static final String FAILED = "failed";
    public static final String RECONCILE_REQUIRED = "reconcile-required";
    static final String INTENT_METADATA = "byclaw_intent";
    static final String OWNER_METADATA = "byclaw_owner";
    static final Duration UNKNOWN_CREATE_SETTLE = Duration.ofMinutes(2);
    private static final String RUNTIME_ENV_PATH = "/run/byclaw-direct/runtime.env";

    /** Cross-replica owner lock; release must compare its token. */
    public interface EnsureLock {
        Optional<String> tryAcquire(String key, Duration ttl);

        void release(String key, String token);
    }

    /** Signed runtime status probe through CubeProxy. Empty when the runtime does not answer. */
    public interface RuntimeProbe {
        Optional<JSONObject> status(RuntimeBinding binding, String runtimeSecret);
    }

    /** Server-owned per-owner state directory on the Cube node. Returns the host path. */
    public interface OwnerDirectories {
        String prepare(RuntimeOwnerScope owner);
    }

    /** A runtime the gateway may send admitted turns to. Contains secrets: never log. */
    public record ReadyRuntime(long bindingId, long generation, String ownerScope, String sandboxId,
                               String trafficToken, String runtimeSecret) {
        @Override
        public String toString() {
            return "ReadyRuntime[bindingId=" + bindingId + ", generation=" + generation + ", sandboxId=" + sandboxId + "]";
        }
    }

    /** The runtime cannot serve the request now; {@code retryable} distinguishes waiting from failure. */
    public static class RuntimeUnavailableException extends RuntimeException {
        private final String state;
        private final boolean retryable;

        public RuntimeUnavailableException(String state, boolean retryable, String message, Throwable cause) {
            super(message, cause);
            this.state = state;
            this.retryable = retryable;
        }

        public String state() {
            return state;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final DirectRuntimeProperties properties;
    private final RuntimeBindingMapper mapper;
    private final CubeSandboxClient cube;
    private final EnsureLock lock;
    private final RuntimeProbe probe;
    private final OwnerDirectories directories;
    private final LongSupplier ids;
    private final Clock clock;
    private final Map<String, CompletableFuture<ReadyRuntime>> inflight = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public RuntimeBindingService(DirectRuntimeProperties properties, RuntimeBindingMapper mapper, CubeSandboxClient cube,
                                 EnsureLock lock, RuntimeProbe probe, OwnerDirectories directories, LongSupplier ids,
                                 Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.cube = cube;
        this.lock = lock;
        this.probe = probe;
        this.directories = directories;
        this.ids = ids;
        this.clock = clock;
    }

    public String runtimeSecret(RuntimeOwnerScope owner, long bindingId) {
        return DirectProtocol.runtimeSecret(properties.getMasterSecret(), owner.key(), bindingId);
    }

    /** Current binding without side effects; reading history never wakes a runtime. */
    public Optional<RuntimeBinding> find(RuntimeOwnerScope owner) {
        return Optional.ofNullable(mapper.selectByOwner(owner.key()));
    }

    /** The owner's runtime only if it is ready and running now; never creates, resumes or waits. */
    public Optional<ReadyRuntime> readyWithoutWake(RuntimeOwnerScope owner) {
        RuntimeBinding binding = mapper.selectByOwner(owner.key());
        if (binding == null || !READY.equals(binding.getState()) || StringUtils.isBlank(binding.getSandboxId())) {
            return Optional.empty();
        }
        boolean running = cube.get(binding.getSandboxId()).map(sandbox -> "running".equalsIgnoreCase(sandbox.state()))
            .orElse(false);
        return running ? Optional.of(new ReadyRuntime(binding.getBindingId(), binding.getGeneration(), owner.key(),
            binding.getSandboxId(), binding.getTrafficToken(), runtimeSecret(owner, binding.getBindingId())))
            : Optional.empty();
    }

    public ReadyRuntime ensure(RuntimeOwnerScope owner) {
        CompletableFuture<ReadyRuntime> mine = new CompletableFuture<>();
        CompletableFuture<ReadyRuntime> current = inflight.putIfAbsent(owner.key(), mine);
        if (current != null) return join(current);
        try {
            mine.complete(ensureLocked(owner));
        }
        catch (RuntimeException e) {
            mine.completeExceptionally(e);
        }
        finally {
            inflight.remove(owner.key(), mine);
        }
        return join(mine);
    }

    private static ReadyRuntime join(CompletableFuture<ReadyRuntime> future) {
        try {
            return future.join();
        }
        catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw e;
        }
    }

    private ReadyRuntime ensureLocked(RuntimeOwnerScope owner) {
        String lockKey = "byclaw:direct:ensure:" + owner.key();
        Duration ttl = properties.getRuntime().getEnsureLockTtl();
        long deadline = clock.millis() + ttl.toMillis();
        Optional<String> token;
        while ((token = lock.tryAcquire(lockKey, ttl)).isEmpty()) {
            if (clock.millis() > deadline) {
                throw new RuntimeUnavailableException(PROVISIONING, true, "另一个请求正在准备运行环境，请稍后重试", null);
            }
            sleep(Duration.ofMillis(250));
        }
        try {
            return ensureBinding(owner);
        }
        finally {
            lock.release(lockKey, token.get());
        }
    }

    private ReadyRuntime ensureBinding(RuntimeOwnerScope owner) {
        RuntimeBinding binding = loadOrCreate(owner);
        String secret = runtimeSecret(owner, binding.getBindingId());
        switch (binding.getState()) {
            case PROVISIONING -> binding = createInstance(owner, binding, secret);
            case RECONCILE_REQUIRED -> binding = reconcileUnknownCreate(owner, binding, secret);
            default -> {
                if (StringUtils.isBlank(binding.getSandboxId())) {
                    throw new RuntimeUnavailableException(binding.getState(), false, "运行环境记录缺少实例，需要人工协调", null);
                }
            }
        }
        binding = resumeIfNeeded(binding);
        if (STARTING.equals(binding.getState())) {
            // Also covers a controller that stopped between recording the instance and bootstrap;
            // the launcher consumes only the first environment it sees.
            bootstrap(owner, binding, secret);
        }
        return awaitReady(owner, binding, secret);
    }

    private RuntimeBinding loadOrCreate(RuntimeOwnerScope owner) {
        RuntimeBinding existing = mapper.selectByOwner(owner.key());
        if (existing != null) return existing;
        RuntimeBinding binding = new RuntimeBinding();
        binding.setBindingId(ids.getAsLong());
        binding.setOwnerScope(owner.key());
        binding.setUserId(owner.userId());
        binding.setUserCode(owner.userCode());
        binding.setProvider(PROVIDER);
        binding.setTemplateId(requireTemplate());
        binding.setGeneration(1L);
        binding.setState(PROVISIONING);
        binding.setCreateIntentId(UUID.randomUUID().toString());
        binding.setHostPath(directories.prepare(owner));
        try {
            mapper.insert(binding);
            return binding;
        }
        catch (DuplicateKeyException e) {
            // Another replica committed the owner first; its binding is authoritative.
            return mapper.selectByOwner(owner.key());
        }
    }

    private RuntimeBinding createInstance(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        Optional<CubeSandbox> adopted = findByIntent(binding);
        CubeSandbox sandbox;
        if (adopted.isPresent()) {
            sandbox = adopted.get();
        }
        else {
            directories.prepare(owner);
            try {
                sandbox = cube.create(binding.getTemplateId(), Map.of(), createMetadata(owner, binding));
            }
            catch (CubeException e) {
                if (e.outcomeUnknown()) {
                    mapper.transition(binding.getBindingId(), binding.getGeneration(), PROVISIONING, RECONCILE_REQUIRED,
                        "create outcome unknown");
                    throw new RuntimeUnavailableException(RECONCILE_REQUIRED, true, "运行环境创建结果待确认，请稍后重试", e);
                }
                mapper.transition(binding.getBindingId(), binding.getGeneration(), PROVISIONING, PROVISIONING,
                    StringUtils.abbreviate(e.getMessage(), 500));
                throw new RuntimeUnavailableException(PROVISIONING, e.capacity(),
                    e.capacity() ? "运行资源暂时不足，已保留原会话，请稍后重试" : "运行环境创建失败", e);
            }
        }
        if (mapper.recordInstance(binding.getBindingId(), binding.getGeneration(), binding.getState(), STARTING,
            sandbox.sandboxId(), sandbox.trafficAccessToken(), sandbox.envdAccessToken()) != 1) {
            throw new RuntimeUnavailableException(binding.getState(), true, "运行环境状态已被其他请求更新，请重试", null);
        }
        return mapper.selectByOwner(owner.key());
    }

    private RuntimeBinding reconcileUnknownCreate(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        if (findByIntent(binding).isPresent()) return createInstance(owner, binding, secret);
        long age = clock.millis() - (binding.getUpdateTime() == null ? 0L : binding.getUpdateTime().getTime());
        if (age < UNKNOWN_CREATE_SETTLE.toMillis()) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, true, "运行环境创建结果待确认，请稍后重试", null);
        }
        // The intent is confirmed absent after the settle window: retry with a fenced new generation.
        if (mapper.renewIntent(binding.getBindingId(), binding.getGeneration(), RECONCILE_REQUIRED, PROVISIONING,
            UUID.randomUUID().toString()) != 1) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, true, "运行环境状态已被其他请求更新，请重试", null);
        }
        RuntimeBinding renewed = mapper.selectByOwner(owner.key());
        return createInstance(owner, renewed, runtimeSecret(owner, renewed.getBindingId()));
    }

    private Optional<CubeSandbox> findByIntent(RuntimeBinding binding) {
        List<CubeSandbox> found = cube.listByMetadata(Map.of(INTENT_METADATA, binding.getCreateIntentId()));
        if (found.size() > 1) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, false, "同一创建意图存在多个实例，需要人工协调", null);
        }
        return found.stream().findFirst();
    }

    private RuntimeBinding resumeIfNeeded(RuntimeBinding binding) {
        Optional<CubeSandbox> current = cube.get(binding.getSandboxId());
        if (current.isEmpty()) {
            // Never replace a missing instance with an empty one; data recovery is a separate operation.
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                "runtime instance missing");
            throw new RuntimeUnavailableException(FAILED, false, "运行环境实例不存在，已保留会话数据，需要恢复", null);
        }
        String cubeState = StringUtils.defaultString(current.get().state()).toLowerCase(java.util.Locale.ROOT);
        if ("running".equals(cubeState)) return binding;
        if (!"paused".equals(cubeState)) {
            // A stopped/errored instance lost its processes; restarting or replacing it is a recovery decision.
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                "runtime instance " + cubeState);
            throw new RuntimeUnavailableException(FAILED, false, "运行环境实例已停止，已保留会话数据，需要恢复", null);
        }
        CubeSandbox resumed;
        try {
            resumed = cube.connect(binding.getSandboxId());
        }
        catch (CubeException e) {
            throw new RuntimeUnavailableException(PAUSED, e.capacity() || e.outcomeUnknown(),
                e.capacity() ? "运行资源暂时不足，已保留原会话，请稍后重试" : "运行环境唤醒失败", e);
        }
        String traffic = StringUtils.defaultIfBlank(resumed.trafficAccessToken(), binding.getTrafficToken());
        String envd = StringUtils.defaultIfBlank(resumed.envdAccessToken(), binding.getEnvdToken());
        mapper.recordInstance(binding.getBindingId(), binding.getGeneration(), binding.getState(), binding.getState(),
            binding.getSandboxId(), traffic, envd);
        return mapper.selectByOwner(binding.getOwnerScope());
    }

    private ReadyRuntime awaitReady(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        long deadline = clock.millis() + properties.getRuntime().getReadyTimeout().toMillis();
        while (true) {
            Optional<JSONObject> status = probe.status(binding, secret);
            if (status.isPresent() && matches(status.get(), owner, binding)) {
                if (!READY.equals(binding.getState())) {
                    mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), READY, null);
                }
                return new ReadyRuntime(binding.getBindingId(), binding.getGeneration(), owner.key(),
                    binding.getSandboxId(), binding.getTrafficToken(), secret);
            }
            if (status.isPresent()) {
                // A runtime answering for another owner or generation must never receive this owner's turns.
                mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                    "runtime identity mismatch");
                throw new RuntimeUnavailableException(FAILED, false, "运行环境身份校验失败", null);
            }
            if (clock.millis() > deadline) {
                throw new RuntimeUnavailableException(binding.getState(), true, "运行环境启动超时，请稍后重试", null);
            }
            sleep(properties.getRuntime().getReadyPollInterval());
        }
    }

    private static boolean matches(JSONObject status, RuntimeOwnerScope owner, RuntimeBinding binding) {
        return DirectProtocol.PROTOCOL.equals(status.getString("protocol"))
            && owner.key().equals(status.getString("ownerScope"))
            && binding.getGeneration().equals(status.getLong("generation"))
            && Boolean.TRUE.equals(status.getBoolean("ready"));
    }

    private void bootstrap(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        Map<String, String> env = new LinkedHashMap<>(properties.getRuntime().getEnv());
        env.putAll(properties.getRuntime().getSecretEnv());
        env.put("USER_CODE", owner.userCode());
        env.put("BYCLAW_DIRECT_SECRET", secret);
        env.put("BYCLAW_DIRECT_OWNER_SCOPE", owner.key());
        env.put("BYCLAW_DIRECT_GENERATION", String.valueOf(binding.getGeneration()));
        env.put("BYCLAW_DIRECT_MOUNT", properties.getRuntime().getMountPath());
        env.put("BYCLAW_DIRECT_PORT", String.valueOf(properties.getRuntime().getPort()));
        // The native DSH Web UI is never exposed to users; lock it with an unshared token.
        byte[] webToken = new byte[32];
        random.nextBytes(webToken);
        env.put("BYCLAW_DSH_WEB_AUTH_TOKEN", HexFormat.of().formatHex(webToken));
        StringBuilder content = new StringBuilder();
        env.forEach((key, value) -> content.append(key).append('=')
            .append(Base64.getEncoder().encodeToString(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8)))
            .append('\n'));
        cube.writeFile(binding.getSandboxId(), binding.getTrafficToken(), binding.getEnvdToken(), RUNTIME_ENV_PATH,
            "root", content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, String> createMetadata(RuntimeOwnerScope owner, RuntimeBinding binding) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(OWNER_METADATA, owner.directoryName());
        metadata.put(INTENT_METADATA, binding.getCreateIntentId());
        Map<String, Object> mount = new LinkedHashMap<>();
        mount.put("hostPath", binding.getHostPath());
        mount.put("mountPath", properties.getRuntime().getMountPath());
        mount.put("readOnly", false);
        metadata.put("host-mount", JSON.toJSONString(List.of(mount)));
        return metadata;
    }

    private String requireTemplate() {
        String template = properties.getCube().getTemplateId();
        if (StringUtils.isBlank(template)) throw new IllegalStateException("byclaw.direct.cube.template-id is required");
        return template;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeUnavailableException(PROVISIONING, true, "运行环境准备被中断", e);
        }
    }
}
