package com.iwhalecloud.byai.gateway.direct;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.mapper.ProjectionProgressMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RequestAdmissionMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeBindingMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;
import com.iwhalecloud.byai.gateway.route.RouteService;
import com.iwhalecloud.byai.state.domain.chat.service.OutputStreamManager;
import com.iwhalecloud.byai.state.domain.chat.service.RunningOutputStreamRegistry;
import com.iwhalecloud.byai.state.domain.chat.service.StreamRecordProcessor;
import com.iwhalecloud.byai.state.domain.sys.service.SequenceService;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Direct HARNESS runtime beans; absent unless byclaw.direct.enabled=true. */
@Slf4j
@Configuration
@EnableConfigurationProperties(DirectRuntimeProperties.class)
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectRuntimeConfiguration {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    @Bean
    public CubeSandboxClient directCubeSandboxClient(DirectRuntimeProperties properties) {
        return new CubeSandboxClient(properties.getCube());
    }

    /**
     * Named outside the global "*Service" transaction proxy on purpose: the create intent and every
     * lifecycle transition must commit before the next Cube call, never roll back with a later failure.
     */
    @Bean
    public RuntimeBindingService directRuntimeBindings(DirectRuntimeProperties properties, RuntimeBindingMapper mapper,
                                                       CubeSandboxClient cube, RedisTemplate<String, Object> redis,
                                                       SequenceService sequenceService) {
        RuntimeBindingService.EnsureLock lock = new RuntimeBindingService.EnsureLock() {
            @Override
            public Optional<String> tryAcquire(String key, Duration ttl) {
                String token = UUID.randomUUID().toString();
                boolean acquired = Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(key, token, ttl.toSeconds(), TimeUnit.SECONDS));
                return acquired ? Optional.of(token) : Optional.empty();
            }

            @Override
            public void release(String key, String token) {
                redis.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
            }
        };
        int port = properties.getRuntime().getPort();
        RuntimeBindingService.RuntimeProbe probe = (binding, secret) -> probeStatus(cube, binding, secret, port);
        return new RuntimeBindingService(properties, mapper, cube, lock, probe,
            owner -> prepareOwnerDirectory(properties.getRuntime(), owner), sequenceService::nextVal, Clock.systemUTC());
    }

    @Bean
    public DirectTransactions directTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new DirectTransactions() {
            @Override
            public <T> T requiresNew(java.util.function.Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    public DirectRouteSelector directRouteSelector(DirectRuntimeProperties properties, RuntimeSessionRouteMapper routes,
                                                   DirectTransactions directTransactions) {
        return new DirectRouteSelector(properties, routes, directTransactions);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService directTurnExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("direct-turn-", 0).factory());
    }

    @Bean
    public DirectAdmissions directAdmissions(RequestAdmissionMapper mapper, DirectTransactions directTransactions) {
        return new DirectAdmissions(mapper, directTransactions);
    }

    @Bean
    public DirectTurnGate directTurnGate(DirectRouteSelector routes, DirectAdmissions admissions) {
        return new DirectTurnGate(routes, admissions);
    }

    @Bean
    public DirectChatGateway directChatGateway(RouteService routeService, RuntimeBindingService bindings,
                                               CubeSandboxClient cube, StreamRecordProcessor processor,
                                               OutputStreamManager outputStreamManager,
                                               RunningOutputStreamRegistry runningRegistry, DirectAdmissions admissions,
                                               DirectRuntimeProperties properties, ExecutorService directTurnExecutor,
                                               ProjectionProgressMapper progressMapper,
                                               RuntimeBindingMapper bindingMapper,
                                               DirectTransactions directTransactions) {
        int port = properties.getRuntime().getPort();
        DirectChatGateway.TurnTransport transport = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(RuntimeBindingService.ReadyRuntime runtime, DirectProtocol.SignedTurn turn)
                throws IOException {
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port,
                        DirectProtocol.ROUTE_PREFIX + "/turns")
                    .header(DirectProtocol.ADMISSION_HEADER, turn.admission())
                    .header(DirectProtocol.SIGNATURE_HEADER, turn.signature())
                    .post(RequestBody.create(turn.body(), MediaType.get("application/json")))
                    .build();
                Response response = cube.stream(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    String error = null;
                    try {
                        JSONObject body = response.body() == null ? null : JSON.parseObject(response.body().string());
                        error = body == null ? null : body.getString("error");
                    }
                    catch (RuntimeException ignored) {
                        error = null;
                    }
                    finally {
                        response.close();
                    }
                    // CubeProxy/runtime 4xx and 503 mean nothing was admitted; other failures are unknown.
                    if (code < 500 || code == 503) throw new DirectChatGateway.RuntimeRefusedException(code, error);
                    throw new IOException("Direct runtime failed: HTTP " + code);
                }
                return new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            }

            @Override
            public java.util.List<JSONObject> readOutbox(RuntimeBindingService.ReadyRuntime runtime, long sessionId,
                                                         long after) throws IOException {
                String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/outbox";
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path + "?after=" + after)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), "GET",
                        path, System.currentTimeMillis()))
                    .get().build();
                try (Response response = cube.call(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Direct outbox read failed: HTTP " + response.code());
                    }
                    java.util.List<JSONObject> items = new java.util.ArrayList<>();
                    for (String line : response.body().string().split("\n")) {
                        if (!line.isBlank()) items.add(JSON.parseObject(line));
                    }
                    return items;
                }
            }

            @Override
            public void ackOutbox(RuntimeBindingService.ReadyRuntime runtime, long sessionId, long throughSeq)
                throws IOException {
                String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/outbox/ack";
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), "POST",
                        path, System.currentTimeMillis()))
                    .post(RequestBody.create("{\"throughSeq\":" + throughSeq + "}", MediaType.get("application/json")))
                    .build();
                try (Response response = cube.call(request).execute()) {
                    if (!response.isSuccessful()) throw new IOException("Direct outbox ack failed: HTTP " + response.code());
                }
            }

            @Override
            public Map<Long, Long> pendingProjection(RuntimeBindingService.ReadyRuntime runtime) throws IOException {
                JSONObject status = probeStatus(cube, runtime.sandboxId(), runtime.trafficToken(), runtime.runtimeSecret(), port)
                    .orElseThrow(() -> new IOException("Direct runtime status unavailable"));
                JSONObject pending = status.getJSONObject("pendingProjection");
                Map<Long, Long> result = new java.util.LinkedHashMap<>();
                if (pending != null) pending.forEach((session, count) -> result.put(Long.valueOf(session), Long.valueOf(String.valueOf(count))));
                return result;
            }

            @Override
            public Optional<String> turnState(RuntimeBindingService.ReadyRuntime runtime, long sessionId, String requestId) {
                String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/turns/"
                    + java.net.URLEncoder.encode(requestId, StandardCharsets.UTF_8);
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), "GET",
                        path, System.currentTimeMillis()))
                    .get().build();
                try (Response response = cube.call(request).execute()) {
                    if (response.code() == 404) return Optional.of("not_admitted");
                    if (!response.isSuccessful() || response.body() == null) return Optional.empty();
                    return Optional.ofNullable(JSON.parseObject(response.body().string()).getString("state"));
                }
                catch (IOException | RuntimeException e) {
                    return Optional.empty();
                }
            }
        };
        DirectChatGateway.ProjectionProgress progress = new DirectChatGateway.ProjectionProgress() {
            @Override
            public long throughSeq(long sessionId) {
                Long value = directTransactions.requiresNew(() -> progressMapper.throughSeq(sessionId));
                return value == null ? 0L : value;
            }

            @Override
            public void advance(long sessionId, String ownerScope, long throughSeq) {
                directTransactions.requiresNew(() -> {
                    if (progressMapper.throughSeq(sessionId) == null) {
                        try {
                            progressMapper.insert(sessionId, ownerScope);
                        }
                        catch (org.springframework.dao.DuplicateKeyException ignored) {
                            // A concurrent turn created the row; advancing is still correct.
                        }
                    }
                    return progressMapper.advance(sessionId, throughSeq);
                });
            }

            @Override
            public java.util.List<com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission> openAdmissions(
                int settleSeconds, int limit) {
                return directTransactions.requiresNew(() -> progressMapper.openAdmissions(settleSeconds, limit));
            }

            @Override
            public java.util.List<String> readyOwners() {
                return directTransactions.requiresNew(bindingMapper::selectReadyOwners);
            }
        };
        return new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager, runningRegistry,
            admissions, properties, directTurnExecutor, Clock.systemUTC(), () -> Runtime.getRuntime().halt(137))
            .withProjectionProgress(progress);
    }

    /** Converges platform history with the runtime outbox without any browser connected. */
    @Bean
    public DirectProjectionReconciler directProjectionReconciler(DirectChatGateway directChatGateway) {
        return new DirectProjectionReconciler(directChatGateway);
    }

    static Optional<JSONObject> probeStatus(CubeSandboxClient cube, RuntimeBinding binding, String secret, int port) {
        return probeStatus(cube, binding.getSandboxId(), binding.getTrafficToken(), secret, port);
    }

    static Optional<JSONObject> probeStatus(CubeSandboxClient cube, String sandboxId, String trafficToken, String secret,
                                            int port) {
        String path = DirectProtocol.ROUTE_PREFIX + "/status";
        Request request = cube.sandboxRequest(sandboxId, trafficToken, port, path)
            .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(secret, "GET", path, System.currentTimeMillis()))
            .get().build();
        try (Response response = cube.call(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return Optional.empty();
            return Optional.ofNullable(JSON.parseObject(response.body().string()));
        }
        catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Owner directory under the Cube host-mount prefix, 0700 for the guest runtime user only. */
    static String prepareOwnerDirectory(DirectRuntimeProperties.Runtime runtime, RuntimeOwnerScope owner) {
        Path local = Path.of(runtime.getLocalMountRoot()).resolve(owner.directoryName());
        try {
            Path parent = local.getParent();
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(local)) {
                throw new IllegalStateException("Direct state mount must not be a symbolic link");
            }
            Files.createDirectories(local);
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rwx------"));
            Object uid = Files.getAttribute(local, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (!Integer.valueOf(runtime.getGuestUid()).equals(uid)) {
                Files.setAttribute(local, "unix:uid", runtime.getGuestUid(), LinkOption.NOFOLLOW_LINKS);
                Files.setAttribute(local, "unix:gid", runtime.getGuestGid(), LinkOption.NOFOLLOW_LINKS);
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("Direct owner state directory is unavailable", e);
        }
        return Path.of(runtime.getHostMountRoot()).resolve(owner.directoryName()).toString();
    }
}
