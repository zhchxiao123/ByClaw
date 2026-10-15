package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.CubeSandboxClient.CubeException;
import com.iwhalecloud.byai.gateway.direct.CubeSandboxClient.CubeSandbox;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.RuntimeUnavailableException;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeBindingMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

import okhttp3.OkHttpClient;

class RuntimeBindingServiceTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 0L);
    private static final RuntimeOwnerScope OTHER = new RuntimeOwnerScope(200L, "u2", 0L);

    private final AtomicLong now = new AtomicLong(1_790_000_000_000L);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };
    private InMemoryBindings bindings;
    private FakeCube cube;
    private Map<String, JSONObject> statuses;
    private DirectRuntimeProperties properties;
    private RuntimeBindingService service;

    @BeforeEach
    void setUp() {
        properties = new DirectRuntimeProperties();
        properties.setMasterSecret("master-secret-for-tests-0123456789abcdef");
        properties.getCube().setTemplateId("tpl-direct");
        properties.getRuntime().setReadyTimeout(Duration.ofSeconds(2));
        properties.getRuntime().setReadyPollInterval(Duration.ofMillis(1));
        properties.getRuntime().getEnv().put("BYCLAW_DSH_BASE_URL", "http://be.internal");
        properties.getRuntime().getSecretEnv().put("REDIS_PASSWORD", "redis-secret");
        bindings = new InMemoryBindings(now);
        cube = new FakeCube();
        statuses = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong(500);
        Map<String, String> locks = new ConcurrentHashMap<>();
        service = new RuntimeBindingService(properties, bindings, cube,
            new RuntimeBindingService.EnsureLock() {
                @Override
                public Optional<String> tryAcquire(String key, Duration ttl) {
                    String token = Thread.currentThread().getName() + System.nanoTime();
                    return locks.putIfAbsent(key, token) == null ? Optional.of(token) : Optional.empty();
                }

                @Override
                public void release(String key, String token) {
                    locks.remove(key, token);
                }
            },
            (binding, secret) -> Optional.ofNullable(statuses.get(binding.getSandboxId())),
            owner -> "/data/shared/byclaw-direct/" + owner.directoryName(),
            ids::incrementAndGet, clock);
        cube.onBootstrap = (sandboxId, env) -> statuses.put(sandboxId, status(env.get("BYCLAW_DIRECT_OWNER_SCOPE"),
            Long.parseLong(env.get("BYCLAW_DIRECT_GENERATION"))));
    }

    @Test
    void firstUseCreatesOnePrivateRuntimeAndWaitsForSignedReadiness() {
        ReadyRuntime runtime = service.ensure(OWNER);

        assertThat(cube.created).hasSize(1);
        Map<String, String> metadata = cube.created.get(0);
        assertThat(metadata).containsEntry(RuntimeBindingService.OWNER_METADATA, OWNER.directoryName());
        assertThat(metadata.get(RuntimeBindingService.INTENT_METADATA)).isNotBlank();
        assertThat(JSON.parseArray(metadata.get("host-mount")).getJSONObject(0).getString("hostPath"))
            .isEqualTo("/data/shared/byclaw-direct/" + OWNER.directoryName());
        assertThat(cube.createdEnvVars.get(0)).as("secrets are not sent as Cube create envVars").isEmpty();

        RuntimeBinding stored = bindings.selectByOwner(OWNER.key());
        assertThat(stored.getState()).isEqualTo(RuntimeBindingService.READY);
        assertThat(stored.getGeneration()).isEqualTo(1L);
        assertThat(runtime.sandboxId()).isEqualTo(stored.getSandboxId());
        assertThat(runtime.runtimeSecret()).isEqualTo(service.runtimeSecret(OWNER, stored.getBindingId()));
        assertThat(runtime.toString()).doesNotContain(runtime.runtimeSecret()).doesNotContain("traffic-");

        Map<String, String> env = cube.bootstrapped.get(stored.getSandboxId());
        assertThat(env).containsEntry("USER_CODE", "u1").containsEntry("BYCLAW_DIRECT_OWNER_SCOPE", "u100.e0")
            .containsEntry("BYCLAW_DIRECT_SECRET", runtime.runtimeSecret())
            .containsEntry("REDIS_PASSWORD", "redis-secret")
            .containsEntry("BYCLAW_DSH_BASE_URL", "http://be.internal");
        assertThat(cube.bootstrapUsers).containsOnly("root");

        service.ensure(OWNER);
        assertThat(cube.created).as("an existing ready runtime is reused").hasSize(1);
    }

    @Test
    void concurrentEnsuresForOneOwnerCreateOnlyOneInstance() throws Exception {
        cube.createDelay = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ReadyRuntime>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) results.add(pool.submit(() -> service.ensure(OWNER)));
            Thread.sleep(50);
            cube.createDelay.countDown();
            for (Future<ReadyRuntime> result : results) assertThat(result.get().ownerScope()).isEqualTo("u100.e0");
        }
        finally {
            pool.shutdownNow();
        }
        assertThat(cube.created).hasSize(1);
    }

    @Test
    void differentOwnersNeverShareARuntimeOrSecret() {
        ReadyRuntime first = service.ensure(OWNER);
        ReadyRuntime second = service.ensure(OTHER);
        assertThat(second.sandboxId()).isNotEqualTo(first.sandboxId());
        assertThat(second.runtimeSecret()).isNotEqualTo(first.runtimeSecret());
        assertThat(bindings.selectByOwner(OTHER.key()).getHostPath())
            .isNotEqualTo(bindings.selectByOwner(OWNER.key()).getHostPath());
    }

    @Test
    void unknownCreateOutcomeIsReconciledByIntentInsteadOfCreatingAgain() {
        cube.failNextCreate = new CubeException("timeout", 0, true, null);
        assertThatThrownBy(() -> service.ensure(OWNER))
            .isInstanceOfSatisfying(RuntimeUnavailableException.class, error -> {
                assertThat(error.retryable()).isTrue();
                assertThat(error.state()).isEqualTo(RuntimeBindingService.RECONCILE_REQUIRED);
            });
        assertThat(bindings.selectByOwner(OWNER.key()).getState()).isEqualTo(RuntimeBindingService.RECONCILE_REQUIRED);

        assertThatThrownBy(() -> service.ensure(OWNER)).isInstanceOf(RuntimeUnavailableException.class);
        assertThat(cube.created).as("no blind second create inside the settle window").hasSize(1);

        cube.materializeLastCreate();
        ReadyRuntime runtime = service.ensure(OWNER);
        assertThat(cube.created).as("the in-flight instance is adopted").hasSize(1);
        assertThat(runtime.sandboxId()).isEqualTo(cube.lastSandboxId);
    }

    @Test
    void confirmedAbsentUnknownCreateRetriesWithAFencedGeneration() {
        cube.failNextCreate = new CubeException("timeout", 0, true, null);
        assertThatThrownBy(() -> service.ensure(OWNER)).isInstanceOf(RuntimeUnavailableException.class);
        now.addAndGet(RuntimeBindingService.UNKNOWN_CREATE_SETTLE.toMillis() + 1);
        ReadyRuntime runtime = service.ensure(OWNER);
        assertThat(runtime.generation()).isEqualTo(2L);
        assertThat(cube.created).hasSize(2);
        assertThat(cube.created.get(1).get(RuntimeBindingService.INTENT_METADATA))
            .isNotEqualTo(cube.created.get(0).get(RuntimeBindingService.INTENT_METADATA));
    }

    @Test
    void pausedRuntimeIsResumedWithoutCreatingAnother() {
        ReadyRuntime first = service.ensure(OWNER);
        cube.states.put(first.sandboxId(), "paused");
        ReadyRuntime resumed = service.ensure(OWNER);
        assertThat(resumed.sandboxId()).isEqualTo(first.sandboxId());
        assertThat(cube.connected).containsExactly(first.sandboxId());
        assertThat(cube.created).hasSize(1);
    }

    @Test
    void missingInstanceFailsClosedInsteadOfCreatingAnEmptyReplacement() {
        ReadyRuntime first = service.ensure(OWNER);
        cube.states.remove(first.sandboxId());
        assertThatThrownBy(() -> service.ensure(OWNER))
            .isInstanceOfSatisfying(RuntimeUnavailableException.class, error -> assertThat(error.retryable()).isFalse());
        assertThat(bindings.selectByOwner(OWNER.key()).getState()).isEqualTo(RuntimeBindingService.FAILED);
        assertThat(cube.created).hasSize(1);
    }

    @Test
    void aStoppedInstanceFailsClosedWithoutWaitingOrRecreating() {
        ReadyRuntime first = service.ensure(OWNER);
        cube.states.put(first.sandboxId(), "exited");
        assertThatThrownBy(() -> service.ensure(OWNER))
            .isInstanceOfSatisfying(RuntimeUnavailableException.class, error -> {
                assertThat(error.retryable()).isFalse();
                assertThat(error.state()).isEqualTo(RuntimeBindingService.FAILED);
            });
        assertThat(bindings.selectByOwner(OWNER.key()).getLastError()).contains("exited");
        assertThat(cube.created).hasSize(1);
    }

    @Test
    void aRuntimeAnsweringForAnotherOwnerIsNeverUsed() {
        cube.onBootstrap = (sandboxId, env) -> statuses.put(sandboxId, status("u999.e0", 1L));
        assertThatThrownBy(() -> service.ensure(OWNER)).isInstanceOf(RuntimeUnavailableException.class);
        assertThat(bindings.selectByOwner(OWNER.key()).getState()).isEqualTo(RuntimeBindingService.FAILED);
    }

    @Test
    void capacityRejectionIsRetryableAndKeepsTheBinding() {
        cube.failNextCreate = new CubeException("no capacity", 503, false, null);
        assertThatThrownBy(() -> service.ensure(OWNER))
            .isInstanceOfSatisfying(RuntimeUnavailableException.class, error -> assertThat(error.retryable()).isTrue());
        assertThat(bindings.selectByOwner(OWNER.key()).getState()).isEqualTo(RuntimeBindingService.PROVISIONING);
        service.ensure(OWNER);
        assertThat(bindings.selectByOwner(OWNER.key()).getState()).isEqualTo(RuntimeBindingService.READY);
    }

    private static JSONObject status(String owner, long generation) {
        JSONObject status = new JSONObject();
        status.put("protocol", DirectProtocol.PROTOCOL);
        status.put("ownerScope", owner);
        status.put("generation", generation);
        status.put("ready", true);
        return status;
    }

    /** Behaves like CubeAPI for the calls the binding service makes. */
    private static final class FakeCube extends CubeSandboxClient {
        final List<Map<String, String>> created = new ArrayList<>();
        final List<Map<String, String>> createdEnvVars = new ArrayList<>();
        final Map<String, String> states = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> metadata = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> bootstrapped = new ConcurrentHashMap<>();
        final List<String> bootstrapUsers = new ArrayList<>();
        final List<String> connected = new ArrayList<>();
        java.util.function.BiConsumer<String, Map<String, String>> onBootstrap = (id, env) -> { };
        CubeException failNextCreate;
        CountDownLatch createDelay;
        String lastSandboxId;
        private Map<String, String> pendingMetadata;
        private int counter;

        FakeCube() {
            super(new DirectRuntimeProperties.Cube(), new OkHttpClient());
        }

        @Override
        public synchronized CubeSandbox create(String templateId, Map<String, String> envVars, Map<String, String> meta) {
            await();
            created.add(new LinkedHashMap<>(meta));
            createdEnvVars.add(new LinkedHashMap<>(envVars));
            lastSandboxId = "sbx-" + (++counter);
            if (failNextCreate != null) {
                CubeException failure = failNextCreate;
                failNextCreate = null;
                pendingMetadata = new LinkedHashMap<>(meta);
                if (failure.outcomeUnknown()) return throwing(failure);
                created.remove(created.size() - 1);
                throw failure;
            }
            states.put(lastSandboxId, "running");
            metadata.put(lastSandboxId, new LinkedHashMap<>(meta));
            return new CubeSandbox(lastSandboxId, "running", "traffic-" + lastSandboxId, "envd-" + lastSandboxId, meta);
        }

        private CubeSandbox throwing(CubeException failure) {
            throw failure;
        }

        /** The timed-out create actually succeeded on the Cube side. */
        void materializeLastCreate() {
            states.put(lastSandboxId, "running");
            metadata.put(lastSandboxId, pendingMetadata);
        }

        private void await() {
            if (createDelay == null) return;
            try {
                createDelay.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public Optional<CubeSandbox> get(String sandboxId) {
            String state = states.get(sandboxId);
            return state == null ? Optional.empty()
                : Optional.of(new CubeSandbox(sandboxId, state, null, null, metadata.get(sandboxId)));
        }

        @Override
        public List<CubeSandbox> listByMetadata(Map<String, String> filter) {
            List<CubeSandbox> result = new ArrayList<>();
            metadata.forEach((id, meta) -> {
                if (states.containsKey(id) && filter.entrySet().stream()
                    .allMatch(entry -> entry.getValue().equals(meta.get(entry.getKey())))) {
                    result.add(new CubeSandbox(id, states.get(id), "traffic-" + id, "envd-" + id, meta));
                }
            });
            return result;
        }

        @Override
        public CubeSandbox connect(String sandboxId) {
            connected.add(sandboxId);
            states.put(sandboxId, "running");
            return new CubeSandbox(sandboxId, "running", "traffic-" + sandboxId, "envd-" + sandboxId, metadata.get(sandboxId));
        }

        @Override
        public synchronized void writeFile(String sandboxId, String trafficToken, String envdToken, String path,
                                           String user, byte[] content) {
            assertThat(trafficToken).isEqualTo("traffic-" + sandboxId);
            assertThat(envdToken).isEqualTo("envd-" + sandboxId);
            assertThat(path).isEqualTo("/run/byclaw-direct/runtime.env");
            Map<String, String> env = new LinkedHashMap<>();
            for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
                int split = line.indexOf('=');
                env.put(line.substring(0, split),
                    new String(Base64.getDecoder().decode(line.substring(split + 1)), StandardCharsets.UTF_8));
            }
            bootstrapped.put(sandboxId, env);
            bootstrapUsers.add(user);
            onBootstrap.accept(sandboxId, env);
        }
    }

    /** CAS semantics of RuntimeBindingMapper over a map. */
    private static final class InMemoryBindings implements RuntimeBindingMapper {
        private final Map<String, RuntimeBinding> rows = new ConcurrentHashMap<>();
        private final AtomicLong now;

        InMemoryBindings(AtomicLong now) {
            this.now = now;
        }

        @Override
        public synchronized RuntimeBinding selectByOwner(String ownerScope) {
            RuntimeBinding row = rows.get(ownerScope);
            return row == null ? null : copy(row);
        }

        @Override
        public synchronized int insert(RuntimeBinding binding) {
            if (rows.containsKey(binding.getOwnerScope())) {
                throw new org.springframework.dao.DuplicateKeyException("owner_scope");
            }
            RuntimeBinding row = copy(binding);
            row.setUpdateTime(new Date(now.get()));
            rows.put(binding.getOwnerScope(), row);
            return 1;
        }

        @Override
        public synchronized int recordInstance(long bindingId, long generation, String expectedState, String nextState,
                                               String sandboxId, String trafficToken, String envdToken) {
            RuntimeBinding row = find(bindingId, generation, expectedState);
            if (row == null) return 0;
            row.setSandboxId(sandboxId);
            row.setTrafficToken(trafficToken);
            row.setEnvdToken(envdToken);
            row.setState(nextState);
            row.setLastError(null);
            row.setUpdateTime(new Date(now.get()));
            return 1;
        }

        @Override
        public synchronized int transition(long bindingId, long generation, String expectedState, String nextState,
                                           String lastError) {
            RuntimeBinding row = find(bindingId, generation, expectedState);
            if (row == null) return 0;
            row.setState(nextState);
            row.setLastError(lastError);
            row.setUpdateTime(new Date(now.get()));
            return 1;
        }

        @Override
        public synchronized int renewIntent(long bindingId, long generation, String expectedState, String nextState,
                                            String createIntentId) {
            RuntimeBinding row = find(bindingId, generation, expectedState);
            if (row == null) return 0;
            row.setGeneration(generation + 1);
            row.setCreateIntentId(createIntentId);
            row.setSandboxId(null);
            row.setTrafficToken(null);
            row.setEnvdToken(null);
            row.setState(nextState);
            row.setUpdateTime(new Date(now.get()));
            return 1;
        }

        @Override
        public synchronized List<String> selectReadyOwners() {
            return rows.values().stream().filter(row -> RuntimeBindingService.READY.equals(row.getState()))
                .map(RuntimeBinding::getOwnerScope).toList();
        }

        private RuntimeBinding find(long bindingId, long generation, String expectedState) {
            return rows.values().stream().filter(row -> row.getBindingId() == bindingId
                && row.getGeneration() == generation && row.getState().equals(expectedState)).findFirst().orElse(null);
        }

        private static RuntimeBinding copy(RuntimeBinding source) {
            return JSON.parseObject(JSON.toJSONString(source), RuntimeBinding.class);
        }
    }
}
