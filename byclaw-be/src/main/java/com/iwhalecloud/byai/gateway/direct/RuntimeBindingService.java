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
    public static final String DRAINING = "draining";
    public static final String PAUSED = "paused";
    public static final String RESUMING = "resuming";
    public static final String FAILED = "failed";
    public static final String RECONCILE_REQUIRED = "reconcile-required";
    /** A data recovery is restoring the owner's state; the runtime is never ready meanwhile. */
    public static final String RESTORE_PENDING = "restore-pending";
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

    /** Server-owned per-owner state directory on the Cube node. */
    public interface OwnerDirectories {
        /** Create the directory for a binding that has never had an instance; returns the host path. */
        String prepare(RuntimeOwnerScope owner);

        /** Record that the directory holds this binding's state. */
        default void claim(RuntimeOwnerScope owner, long bindingId) {
        }

        /**
         * Whether the directory still holds this binding's state. Never creates it: a missing, empty
         * or foreign directory must not stand in for the owner's data.
         */
        default boolean holds(RuntimeOwnerScope owner, long bindingId) {
            return true;
        }
    }

    /** Signed drain/undrain on the runtime; drain answers 200 only when idle and flushed. */
    public interface RuntimeControl {
        int drain(RuntimeBinding binding, String runtimeSecret);

        void undrain(RuntimeBinding binding, String runtimeSecret);

        RuntimeControl NONE = new RuntimeControl() {
            @Override
            public int drain(RuntimeBinding binding, String runtimeSecret) {
                return 409;
            }

            @Override
            public void undrain(RuntimeBinding binding, String runtimeSecret) {
            }
        };
    }

    /** A runtime the gateway may send admitted turns to. Contains secrets: never log. */
    public record ReadyRuntime(long bindingId, long generation, String ownerScope, String sandboxId,
                               String trafficToken, String runtimeSecret) {
        @Override
        public String toString() {
            return "ReadyRuntime[bindingId=" + bindingId + ", generation=" + generation + ", sandboxId=" + sandboxId + "]";
        }
    }

    /** The runtime cannot serve the request now; the fault says why and whether waiting helps. */
    public static class RuntimeUnavailableException extends RuntimeException {
        private final String state;
        private final RuntimeFault fault;

        public RuntimeUnavailableException(String state, RuntimeFault fault, Throwable cause) {
            super(fault.message(), cause);
            this.state = state;
            this.fault = fault;
        }

        public String state() {
            return state;
        }

        public RuntimeFault fault() {
            return fault;
        }

        public boolean retryable() {
            return fault.retryable();
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
    private RuntimeControl control = RuntimeControl.NONE;
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

    public RuntimeBindingService withControl(RuntimeControl control) {
        this.control = control;
        return this;
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
        return readyWithoutWake(owner.key());
    }

    public Optional<ReadyRuntime> readyWithoutWake(String ownerKey) {
        RuntimeBinding binding = mapper.selectByOwner(ownerKey);
        if (binding == null || !READY.equals(binding.getState()) || StringUtils.isBlank(binding.getSandboxId())) {
            return Optional.empty();
        }
        boolean running = cube.get(binding.getSandboxId()).map(sandbox -> "running".equalsIgnoreCase(sandbox.state()))
            .orElse(false);
        return running ? Optional.of(new ReadyRuntime(binding.getBindingId(), binding.getGeneration(), ownerKey,
            binding.getSandboxId(), binding.getTrafficToken(),
            DirectProtocol.runtimeSecret(properties.getMasterSecret(), ownerKey, binding.getBindingId())))
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
                throw new RuntimeUnavailableException(PROVISIONING, RuntimeFault.BUSY, null);
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
        if (DRAINING.equals(binding.getState())) {
            // A live pause holds this owner lock, so a draining binding seen here was left by a
            // controller that stopped mid-pause. The Cube state decides: a paused instance is
            // resumed, a running one is undrained once its signed status answers.
            log.warn("Direct runtime recovering an interrupted drain, owner={}, sandboxId={}", owner.key(),
                binding.getSandboxId());
        }
        if (RESTORE_PENDING.equals(binding.getState())) {
            throw unavailable(binding, RESTORE_PENDING, RuntimeFault.RESTORE_PENDING, null);
        }
        if (StringUtils.isNotBlank(binding.getSandboxId()) && !directories.holds(owner, binding.getBindingId())) {
            // The instance mounts this directory: never start or resume it over missing or foreign state.
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), binding.getState(),
                RuntimeFault.STATE_MISSING.lastError(binding.getHostPath()));
            throw unavailable(binding, binding.getState(), RuntimeFault.STATE_MISSING, null);
        }
        String secret = runtimeSecret(owner, binding.getBindingId());
        switch (binding.getState()) {
            case PROVISIONING -> binding = createInstance(owner, binding, secret);
            case RECONCILE_REQUIRED -> binding = reconcileUnknownCreate(owner, binding, secret);
            default -> {
                if (StringUtils.isBlank(binding.getSandboxId())) {
                    throw new RuntimeUnavailableException(binding.getState(), RuntimeFault.RECORD_INCOMPLETE, null);
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
            directories.claim(owner, binding.getBindingId());
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
            // No instance has used this binding yet, so there is no earlier state to protect.
            directories.prepare(owner);
            directories.claim(owner, binding.getBindingId());
            try {
                sandbox = cube.create(binding.getTemplateId(), Map.of(), createMetadata(owner, binding));
            }
            catch (CubeException e) {
                if (e.outcomeUnknown()) {
                    mapper.transition(binding.getBindingId(), binding.getGeneration(), PROVISIONING, RECONCILE_REQUIRED,
                        RuntimeFault.CREATE_UNKNOWN.lastError(null));
                    throw unavailable(binding, RECONCILE_REQUIRED, RuntimeFault.CREATE_UNKNOWN, e);
                }
                RuntimeFault fault = e.capacity() ? RuntimeFault.CAPACITY
                    : e.templateUnavailable() ? RuntimeFault.TEMPLATE_UNAVAILABLE : RuntimeFault.CREATE_FAILED;
                // A definite refusal created nothing: the binding stays provisioning and a retry creates once.
                mapper.transition(binding.getBindingId(), binding.getGeneration(), PROVISIONING, PROVISIONING,
                    StringUtils.abbreviate(fault.lastError(e.getMessage()), 500));
                throw unavailable(binding, PROVISIONING, fault, e);
            }
        }
        if (mapper.recordInstance(binding.getBindingId(), binding.getGeneration(), binding.getState(), STARTING,
            sandbox.sandboxId(), sandbox.trafficAccessToken(), sandbox.envdAccessToken()) != 1) {
            throw new RuntimeUnavailableException(binding.getState(), RuntimeFault.CONCURRENT_UPDATE, null);
        }
        return mapper.selectByOwner(owner.key());
    }

    private RuntimeBinding reconcileUnknownCreate(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        if (findByIntent(binding).isPresent()) return createInstance(owner, binding, secret);
        long age = clock.millis() - (binding.getUpdateTime() == null ? 0L : binding.getUpdateTime().getTime());
        if (age < UNKNOWN_CREATE_SETTLE.toMillis()) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, RuntimeFault.CREATE_UNKNOWN, null);
        }
        // The intent is confirmed absent after the settle window: retry with a fenced new generation.
        if (mapper.renewIntent(binding.getBindingId(), binding.getGeneration(), RECONCILE_REQUIRED, PROVISIONING,
            UUID.randomUUID().toString()) != 1) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, RuntimeFault.CONCURRENT_UPDATE, null);
        }
        RuntimeBinding renewed = mapper.selectByOwner(owner.key());
        return createInstance(owner, renewed, runtimeSecret(owner, renewed.getBindingId()));
    }

    private Optional<CubeSandbox> findByIntent(RuntimeBinding binding) {
        List<CubeSandbox> found = cube.listByMetadata(Map.of(INTENT_METADATA, binding.getCreateIntentId()));
        if (found.size() > 1) {
            throw new RuntimeUnavailableException(RECONCILE_REQUIRED, RuntimeFault.DUPLICATE_INSTANCES, null);
        }
        return found.stream().findFirst();
    }

    private RuntimeBinding resumeIfNeeded(RuntimeBinding binding) {
        Optional<CubeSandbox> current = cube.get(binding.getSandboxId());
        if (current.isEmpty()) {
            // Never replace a missing instance with an empty one; data recovery is a separate operation.
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                RuntimeFault.INSTANCE_MISSING.lastError(binding.getSandboxId()));
            throw unavailable(binding, FAILED, RuntimeFault.INSTANCE_MISSING, null);
        }
        String cubeState = StringUtils.defaultString(current.get().state()).toLowerCase(java.util.Locale.ROOT);
        if ("running".equals(cubeState)) {
            if (PAUSED.equals(binding.getState())) {
                // Resumed outside this controller (or a pause was recorded but not executed).
                mapper.transition(binding.getBindingId(), binding.getGeneration(), PAUSED, STARTING, null);
                return mapper.selectByOwner(binding.getOwnerScope());
            }
            return binding;
        }
        if (!"paused".equals(cubeState)) {
            // A stopped/errored instance lost its processes; restarting or replacing it is a recovery decision.
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                RuntimeFault.INSTANCE_STOPPED.lastError(cubeState));
            throw unavailable(binding, FAILED, RuntimeFault.INSTANCE_STOPPED, null);
        }
        CubeSandbox resumed;
        try {
            resumed = cube.connect(binding.getSandboxId());
        }
        catch (CubeException e) {
            // The paused instance and its snapshot stay; a retry resumes the same instance or fails again.
            RuntimeFault fault = e.capacity() ? RuntimeFault.CAPACITY : RuntimeFault.RESUME_FAILED;
            mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), binding.getState(),
                StringUtils.abbreviate(fault.lastError(e.getMessage()), 500));
            throw unavailable(binding, binding.getState(), fault, e);
        }
        log.info("Direct runtime resumed from pause, owner={}, sandboxId={}", binding.getOwnerScope(), binding.getSandboxId());
        String traffic = StringUtils.defaultIfBlank(resumed.trafficAccessToken(), binding.getTrafficToken());
        String envd = StringUtils.defaultIfBlank(resumed.envdAccessToken(), binding.getEnvdToken());
        // Resuming: readiness is proven again by the signed probe before the runtime is ready.
        String next = PAUSED.equals(binding.getState()) || DRAINING.equals(binding.getState()) ? RESUMING : binding.getState();
        mapper.recordInstance(binding.getBindingId(), binding.getGeneration(), binding.getState(), next,
            binding.getSandboxId(), traffic, envd);
        return mapper.selectByOwner(binding.getOwnerScope());
    }

    private ReadyRuntime awaitReady(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        long deadline = clock.millis() + properties.getRuntime().getReadyTimeout().toMillis();
        while (true) {
            Optional<JSONObject> status = probe.status(binding, secret);
            if (status.isPresent() && matches(status.get(), owner, binding)
                && Boolean.TRUE.equals(status.get().getBoolean("draining"))) {
                // The VM was paused while drained; it accepts work again only after an explicit undrain.
                control.undrain(binding, secret);
                if (clock.millis() > deadline) {
                    throw new RuntimeUnavailableException(binding.getState(), RuntimeFault.REOPEN_TIMEOUT, null);
                }
                sleep(properties.getRuntime().getReadyPollInterval());
                continue;
            }
            if (status.isPresent() && matches(status.get(), owner, binding)) {
                if (!READY.equals(binding.getState())) {
                    mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), READY, null);
                }
                return new ReadyRuntime(binding.getBindingId(), binding.getGeneration(), owner.key(),
                    binding.getSandboxId(), binding.getTrafficToken(), secret);
            }
            if (status.isPresent()) {
                // A runtime of another protocol, owner or generation must never receive this owner's turns.
                String protocol = status.get().getString("protocol");
                RuntimeFault fault = DirectProtocol.PROTOCOL.equals(protocol) ? RuntimeFault.IDENTITY_MISMATCH
                    : RuntimeFault.PROTOCOL_INCOMPATIBLE;
                mapper.transition(binding.getBindingId(), binding.getGeneration(), binding.getState(), FAILED,
                    fault.lastError(fault == RuntimeFault.PROTOCOL_INCOMPATIBLE ? StringUtils.abbreviate(protocol, 64) : null));
                throw unavailable(binding, FAILED, fault, null);
            }
            if (clock.millis() > deadline) {
                throw new RuntimeUnavailableException(binding.getState(), RuntimeFault.START_TIMEOUT, null);
            }
            sleep(properties.getRuntime().getReadyPollInterval());
        }
    }

    /**
     * Pause the owner's runtime when it has been idle for the configured time: no work lease, no
     * running turn, nothing left to project. Drain closes admission and flushes durable state first;
     * any failure keeps the instance and its data and returns it to ready. Never deletes.
     * @return true when the runtime was paused.
     */
    public boolean pauseIfIdle(String ownerKey) {
        String lockKey = "byclaw:direct:ensure:" + ownerKey;
        Optional<String> token = lock.tryAcquire(lockKey, properties.getRuntime().getEnsureLockTtl());
        if (token.isEmpty()) return false; // an ensure is in progress; this owner is not idle
        try {
            RuntimeBinding binding = mapper.selectByOwner(ownerKey);
            if (binding == null || !READY.equals(binding.getState()) || StringUtils.isBlank(binding.getSandboxId())) return false;
            // A runtime just made ready (created or resumed) has a turn about to take its lease.
            long readyFor = clock.millis() - (binding.getUpdateTime() == null ? 0L : binding.getUpdateTime().getTime());
            if (readyFor < properties.getRuntime().getIdleTimeout().toMillis()) return false;
            String secret = DirectProtocol.runtimeSecret(properties.getMasterSecret(), ownerKey, binding.getBindingId());
            Optional<JSONObject> status = probe.status(binding, secret);
            // Unknown state is never idle: an unreachable runtime may still be working.
            if (status.isEmpty() || !idle(status.get())) return false;
            if (mapper.transition(binding.getBindingId(), binding.getGeneration(), READY, DRAINING, null) != 1) return false;
            int drained = control.drain(binding, secret);
            if (drained != 200) {
                control.undrain(binding, secret);
                mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, READY,
                    drained == 409 ? null : "drain_failed: " + drained);
                return false;
            }
            // Fence: if this pauser's lock expired during a slow drain, an ensure may have recovered
            // the binding and handed the instance to a turn. Pause only a binding still draining.
            RuntimeBinding fenced = mapper.selectByOwner(ownerKey);
            if (fenced == null || !DRAINING.equals(fenced.getState()) || !binding.getGeneration().equals(fenced.getGeneration())) {
                log.warn("Direct pause abandoned, binding changed during drain, owner={}, state={}", ownerKey,
                    fenced == null ? null : fenced.getState());
                return false;
            }
            try {
                cube.pause(binding.getSandboxId());
            }
            catch (RuntimeException e) {
                control.undrain(binding, secret);
                mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, READY,
                    StringUtils.abbreviate("pause_failed: " + e.getMessage(), 500));
                // The instance keeps running with admission reopened; Cube still holds its quota.
                log.warn("Direct runtime pause failed, owner={}, sandboxId={}, fault=pause_failed", ownerKey,
                    binding.getSandboxId());
                return false;
            }
            mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, PAUSED, null);
            log.info("Direct runtime paused after idle, owner={}, sandboxId={}", ownerKey, binding.getSandboxId());
            return true;
        }
        finally {
            lock.release(lockKey, token.get());
        }
    }

    /**
     * Suspend the runtime of a revoked owner scope without waiting for idle: close admission, then
     * pause the instance. The instance and its data stay; the scope's authority check keeps it from
     * being resumed until access is restored. Never touches another owner.
     * @return true when the runtime was paused.
     */
    public boolean suspend(String ownerKey, String reason) {
        String lockKey = "byclaw:direct:ensure:" + ownerKey;
        Optional<String> token = lock.tryAcquire(lockKey, properties.getRuntime().getEnsureLockTtl());
        if (token.isEmpty()) return false; // retried by the next sweep
        try {
            RuntimeBinding binding = mapper.selectByOwner(ownerKey);
            if (binding == null || !READY.equals(binding.getState()) || StringUtils.isBlank(binding.getSandboxId())) return false;
            if (mapper.transition(binding.getBindingId(), binding.getGeneration(), READY, DRAINING, null) != 1) return false;
            String secret = DirectProtocol.runtimeSecret(properties.getMasterSecret(), ownerKey, binding.getBindingId());
            int drained = control.drain(binding, secret); // 409 means still working; revocation does not wait
            try {
                cube.pause(binding.getSandboxId());
            }
            catch (RuntimeException e) {
                // Admission stays closed on the runtime; the binding records why it is not paused.
                mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, READY,
                    StringUtils.abbreviate("scope_revoked: " + reason + "; pause_failed: " + e.getMessage(), 500));
                log.warn("Direct runtime suspension failed, owner={}, sandboxId={}, reason={}", ownerKey,
                    binding.getSandboxId(), reason);
                return false;
            }
            mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, PAUSED, "scope_revoked: " + reason);
            log.warn("Direct runtime suspended, owner scope revoked, owner={}, sandboxId={}, reason={}, drain={}",
                ownerKey, binding.getSandboxId(), reason, drained);
            return true;
        }
        finally {
            lock.release(lockKey, token.get());
        }
    }

    private boolean idle(JSONObject status) {
        JSONObject leases = status.getJSONObject("leases");
        JSONObject pending = status.getJSONObject("pendingProjection");
        long now = status.getLongValue("now");
        long lastReleased = leases == null ? now : leases.getLongValue("lastReleasedAt");
        return leases != null && leases.getIntValue("active") == 0
            && status.getIntValue("running") == 0
            && (pending == null || pending.isEmpty())
            && !Boolean.TRUE.equals(status.getBoolean("draining"))
            && now - lastReleased >= properties.getRuntime().getIdleTimeout().toMillis();
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
        // Shared secrets serve the runtime itself; the tool environment never receives them.
        if (!properties.getRuntime().getSecretEnv().isEmpty()) {
            env.put("BYCLAW_TOOL_ENV_DENY", String.join(",", properties.getRuntime().getSecretEnv().keySet()));
        }
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

    private RuntimeUnavailableException unavailable(RuntimeBinding binding, String state, RuntimeFault fault, Throwable cause) {
        log.warn("Direct runtime unavailable, owner={}, sandboxId={}, state={}, fault={}", binding.getOwnerScope(),
            binding.getSandboxId(), state, fault.code());
        return new RuntimeUnavailableException(state, fault, cause);
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
            throw new RuntimeUnavailableException(PROVISIONING, RuntimeFault.INTERRUPTED, e);
        }
    }
}
