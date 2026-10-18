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
import java.util.Set;
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
public class RuntimeBindingService implements DirectBackups.Bindings {

    public static final String PROVIDER = "cube";
    public static final String PROVISIONING = "provisioning";
    /** How long delivering the runtime environment to a fresh instance is retried. */
    static final Duration BOOTSTRAP_RETRY_WINDOW = Duration.ofSeconds(30);

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
    private DirectResourceScope resourceScope;
    private java.util.function.Predicate<String> activity = ownerKey -> false;
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

    /** Interaction outside the runtime's own leases (an open desktop viewer); such an owner is not idle. */
    public RuntimeBindingService withActivity(java.util.function.Predicate<String> activity) {
        this.activity = activity;
        return this;
    }

    /** Grants the runtime may see, scoped to its enterprise and refreshed whenever it is ensured. */
    public RuntimeBindingService withResourceScope(DirectResourceScope resourceScope) {
        this.resourceScope = resourceScope;
        return this;
    }

    public RuntimeBindingService withControl(RuntimeControl control) {
        this.control = control;
        return this;
    }

    public String runtimeSecret(RuntimeOwnerScope owner, long bindingId) {
        return DirectProtocol.runtimeSecret(properties.getMasterSecret(), owner.key(), bindingId);
    }

    /** Current binding without side effects; reading history never wakes a runtime. */
    public Optional<RuntimeBinding> find(String ownerKey) {
        return Optional.ofNullable(mapper.selectByOwner(ownerKey));
    }

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
        long started = clock.millis();
        RuntimeBinding binding = loadOrCreate(owner);
        String initial = binding.getState();
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
        try {
            binding = resumeIfNeeded(binding);
        }
        catch (RuntimeUnavailableException e) {
            if (!replaceableAfterResumeFailure(binding, e)) throw e;
            binding = replaceUnresumable(owner, binding, secret);
            binding = resumeIfNeeded(binding);
        }
        refreshResourceScope(owner);
        if (STARTING.equals(binding.getState())) {
            // Also covers a controller that stopped between recording the instance and bootstrap;
            // the launcher consumes only the first environment it sees.
            bootstrap(owner, binding, secret);
        }
        ReadyRuntime ready = awaitReady(owner, binding, secret);
        if (!READY.equals(initial)) {
            // Measured preparation time per path (created, resumed, recovered), for capacity planning.
            log.info("Direct runtime ready, owner={}, from={}, ms={}", owner.key(), initial, clock.millis() - started);
        }
        return ready;
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

    /**
     * A resume is only attempted on an instance Cube reports paused, and this controller pauses only
     * after a drain flushed the owner state to the mount, so the state survives replacing it, whatever
     * the binding recorded (a pause Cube reported as failed leaves the binding ready).
     */
    private boolean replaceableAfterResumeFailure(RuntimeBinding binding, RuntimeUnavailableException e) {
        return properties.getRuntime().isAutoRecreateOnResumeFailure() && e.fault() == RuntimeFault.RESUME_FAILED;
    }

    private boolean pausedAnyway(String sandboxId) {
        try {
            return cube.get(sandboxId).map(found -> "paused".equalsIgnoreCase(found.state())).orElse(false);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Cube could not resume the paused instance (its snapshot did not restore). The owner state is
     * on the mount, so a new instance of the next generation over it continues the owner's work; the
     * broken instance is deleted. Called with the owner's ensure lock held.
     */
    private RuntimeBinding replaceUnresumable(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        RuntimeBinding failed = mapper.selectByOwner(owner.key());
        log.warn("Direct runtime could not resume; replacing the instance over the same owner state, owner={}, "
            + "sandboxId={}, error={}", owner.key(), failed.getSandboxId(), StringUtils.abbreviate(failed.getLastError(), 200));
        try {
            cube.delete(failed.getSandboxId());
        }
        catch (CubeException e) {
            // A broken instance that cannot be deleted is left to Cube; the new one does not depend on it.
            log.warn("Direct runtime unresumable instance not deleted, owner={}, sandboxId={}", owner.key(),
                failed.getSandboxId());
        }
        if (mapper.renewIntent(failed.getBindingId(), failed.getGeneration(), failed.getState(), PROVISIONING,
            UUID.randomUUID().toString()) != 1) {
            throw new IllegalStateException("concurrent_update");
        }
        return createInstance(owner, mapper.selectByOwner(owner.key()), secret);
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
        long connectStarted = clock.millis();
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
        log.info("Direct runtime resumed from pause, owner={}, sandboxId={}, connectMs={}", binding.getOwnerScope(),
            binding.getSandboxId(), clock.millis() - connectStarted);
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
        if (activity.test(ownerKey)) return false; // someone is using the runtime outside its leases
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
                if (pausedAnyway(binding.getSandboxId())) {
                    // Cube reported a failure but paused the drained instance: record what is true,
                    // or the next turn treats a paused instance as running.
                    mapper.transition(binding.getBindingId(), binding.getGeneration(), DRAINING, PAUSED,
                        StringUtils.abbreviate("pause_reported_failed: " + e.getMessage(), 500));
                    log.warn("Direct runtime pause reported a failure but the instance is paused, owner={}, sandboxId={}",
                        ownerKey, binding.getSandboxId());
                    return true;
                }
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
     * Run work on an owner's state while its runtime is paused (or has no instance) and holds the
     * owner lock, so no turn can wake it meanwhile. Refuses anything that may be running.
     */
    @Override
    public <T> T whileQuiesced(String ownerKey, java.util.function.Function<RuntimeBinding, T> work) {
        String lockKey = "byclaw:direct:ensure:" + ownerKey;
        if (activity.test(ownerKey)) throw new IllegalStateException("runtime_in_use");
        Optional<String> token = lock.tryAcquire(lockKey, properties.getRuntime().getBackupLockTtl());
        if (token.isEmpty()) throw new IllegalStateException("runtime_busy");
        try {
            RuntimeBinding binding = mapper.selectByOwner(ownerKey);
            if (binding == null) throw new IllegalStateException("binding_missing");
            boolean quiet = PAUSED.equals(binding.getState())
                || (PROVISIONING.equals(binding.getState()) && StringUtils.isBlank(binding.getSandboxId()));
            if (!quiet) throw new IllegalStateException("runtime_running: " + binding.getState());
            return work.apply(binding);
        }
        finally {
            lock.release(lockKey, token.get());
        }
    }

    /**
     * Start restoring an owner's state: the binding must be the one the backup belongs to and its
     * runtime must not be running. The instance is deleted and the generation advanced, so the old
     * writer is fenced; the binding stays restore-pending, and no runtime starts, until the restore
     * completes or is aborted.
     */
    @Override
    public RuntimeBinding beginRestore(String ownerKey, long bindingId) {
        return replaceInstance(ownerKey, bindingId, RESTORE_PENDING);
    }

    /**
     * Operator recovery for an instance that cannot be resumed or has stopped: delete it and let
     * the next turn create one of a new generation over the same owner state. Never while running.
     */
    public RuntimeBinding recreateInstance(String ownerKey) {
        RuntimeBinding binding = mapper.selectByOwner(ownerKey);
        if (binding == null) throw new IllegalStateException("binding_missing");
        RuntimeBinding replaced = replaceInstance(ownerKey, binding.getBindingId(), PROVISIONING);
        log.warn("Direct runtime instance recreated by an operator, owner={}, previousError={}", ownerKey,
            StringUtils.abbreviate(binding.getLastError(), 200));
        return replaced;
    }

    private RuntimeBinding replaceInstance(String ownerKey, long bindingId, String nextState) {
        String lockKey = "byclaw:direct:ensure:" + ownerKey;
        Optional<String> token = lock.tryAcquire(lockKey, properties.getRuntime().getEnsureLockTtl());
        if (token.isEmpty()) throw new IllegalStateException("runtime_busy");
        try {
            RuntimeBinding binding = mapper.selectByOwner(ownerKey);
            if (binding == null || binding.getBindingId() != bindingId) throw new IllegalStateException("identity_mismatch");
            if (!Set.of(PAUSED, PROVISIONING, FAILED, RECONCILE_REQUIRED, RESTORE_PENDING).contains(binding.getState())) {
                throw new IllegalStateException("runtime_running: " + binding.getState());
            }
            if (StringUtils.isNotBlank(binding.getSandboxId())) cube.delete(binding.getSandboxId());
            if (mapper.renewIntent(binding.getBindingId(), binding.getGeneration(), binding.getState(), nextState,
                UUID.randomUUID().toString()) != 1) {
                throw new IllegalStateException("concurrent_update");
            }
            log.warn("Direct runtime instance replaced, owner={}, previousSandbox={}, generation={}, state={}", ownerKey,
                binding.getSandboxId(), binding.getGeneration() + 1, nextState);
            return mapper.selectByOwner(ownerKey);
        }
        finally {
            lock.release(lockKey, token.get());
        }
    }

    /** The restored state is in place; the next turn creates an instance of the new generation over it. */
    @Override
    public void completeRestore(String ownerKey, long generation) {
        RuntimeBinding binding = mapper.selectByOwner(ownerKey);
        if (binding == null || mapper.transition(binding.getBindingId(), generation, RESTORE_PENDING, PROVISIONING, null) != 1) {
            throw new IllegalStateException("concurrent_update");
        }
    }

    /** The restore was undone and the original state is back in place. */
    @Override
    public void abortRestore(String ownerKey, long generation, String reason) {
        RuntimeBinding binding = mapper.selectByOwner(ownerKey);
        if (binding != null) {
            mapper.transition(binding.getBindingId(), generation, RESTORE_PENDING, PROVISIONING,
                StringUtils.abbreviate(reason, 500));
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

    private void refreshResourceScope(RuntimeOwnerScope owner) {
        if (resourceScope == null) return;
        try {
            resourceScope.refresh(owner);
        }
        catch (RuntimeException e) {
            // The previous copy stays in place; the next ensure or sweep retries.
            log.warn("Direct resource scope refresh failed, owner={}, errorType={}", owner.key(), e.getClass().getSimpleName());
        }
    }

    private void bootstrap(RuntimeOwnerScope owner, RuntimeBinding binding, String secret) {
        Map<String, String> env = new LinkedHashMap<>(properties.getRuntime().getEnv());
        env.putAll(properties.getRuntime().getSecretEnv());
        env.put("USER_CODE", owner.userCode());
        env.put("BYCLAW_DIRECT_SECRET", secret);
        env.put("BYCLAW_DIRECT_OWNER_SCOPE", owner.key());
        env.put("BYCLAW_DIRECT_GENERATION", String.valueOf(binding.getGeneration()));
        env.put("BYCLAW_DIRECT_MOUNT", properties.getRuntime().getMountPath());
        if (properties.getRuntime().userFsEnabled()) env.put("BYCLAW_DIRECT_USER_FS", properties.getRuntime().getUserFsMountPath());
        env.put("BYCLAW_DIRECT_PORT", String.valueOf(properties.getRuntime().getPort()));
        if (resourceScope != null) env.put("BYCLAW_RESOURCE_AUTH_KEY", DirectResourceScope.key(owner));
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
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        // A fresh instance (notably one replacing an unresumable one) can refuse envd for its first
        // seconds; the write is idempotent, so retry within the bootstrap window instead of failing
        // the turn and leaving the instance starting until the user tries again.
        long deadline = clock.millis() + BOOTSTRAP_RETRY_WINDOW.toMillis();
        long backoff = 250L;
        while (true) {
            try {
                cube.writeFile(binding.getSandboxId(), binding.getTrafficToken(), binding.getEnvdToken(), RUNTIME_ENV_PATH,
                    "root", bytes);
                return;
            }
            catch (CubeException e) {
                if (clock.millis() + backoff > deadline) throw e;
                log.info("Direct runtime environment not delivered yet, retrying, owner={}, sandboxId={}, error={}",
                    owner.key(), binding.getSandboxId(), StringUtils.abbreviate(e.getMessage(), 200));
                sleep(Duration.ofMillis(backoff));
                backoff = Math.min(backoff * 2, 4000L);
            }
        }
    }

    private Map<String, String> createMetadata(RuntimeOwnerScope owner, RuntimeBinding binding) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(OWNER_METADATA, owner.directoryName());
        metadata.put(INTENT_METADATA, binding.getCreateIntentId());
        Map<String, Object> mount = new LinkedHashMap<>();
        mount.put("hostPath", binding.getHostPath());
        mount.put("mountPath", properties.getRuntime().getMountPath());
        mount.put("readOnly", false);
        List<Map<String, Object>> mounts = new java.util.ArrayList<>(List.of(mount));
        if (properties.getRuntime().userFsEnabled()) {
            // The user's own /by: personal and project workspaces, shared with the platform's services.
            Map<String, Object> userFs = new LinkedHashMap<>();
            userFs.put("hostPath", userFsPath(properties.getRuntime().getUserFsHostRoot(), owner.userCode()));
            userFs.put("mountPath", properties.getRuntime().getUserFsMountPath());
            userFs.put("readOnly", false);
            mounts.add(userFs);
            prepareUserFs(owner);
        }
        metadata.put("host-mount", JSON.toJSONString(mounts));
        return metadata;
    }

    /** {@code <root>/byclaw-<user>/by}, the Legacy sandboxes' layout of a user's file storage. */
    static String userFsPath(String root, String userCode) {
        return java.nio.file.Path.of(root, com.iwhalecloud.byai.common.storage.util.UserBucketNameResolver
            .buildUserBucketName(userCode), "by").toString();
    }

    /**
     * The mounted directory must exist before Cube mounts it. What earlier builds wrote to the
     * owner state's {@code by} moves into the user's storage once, on this host (a copy between
     * two sandbox mounts is far too slow), without overwriting the user's files.
     */
    private void prepareUserFs(RuntimeOwnerScope owner) {
        java.nio.file.Path userBy = java.nio.file.Path.of(
            userFsPath(properties.getRuntime().userFsLocalRootOrHost(), owner.userCode()));
        java.nio.file.Path ownerBy = java.nio.file.Path.of(properties.getRuntime().getLocalMountRoot(),
            owner.directoryName(), "by");
        try {
            java.nio.file.Files.createDirectories(userBy);
            if (!java.nio.file.Files.isDirectory(ownerBy, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(ownerBy)) {
                if (entries.findAny().isEmpty()) return;
            }
            try (java.util.stream.Stream<java.nio.file.Path> tree = java.nio.file.Files.walk(ownerBy)) {
                for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) tree::iterator) {
                    java.nio.file.Path target = userBy.resolve(ownerBy.relativize(source).toString());
                    if (java.nio.file.Files.isSymbolicLink(source) || java.nio.file.Files.exists(target,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(target);
                    else java.nio.file.Files.copy(source, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            java.nio.file.Files.move(ownerBy, ownerBy.resolveSibling("by.moved-to-user-fs-" + clock.millis()));
            log.info("Direct owner workspace moved into the user's file storage, owner={}", owner.key());
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("Direct user file storage unavailable for " + owner.key(), e);
        }
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
