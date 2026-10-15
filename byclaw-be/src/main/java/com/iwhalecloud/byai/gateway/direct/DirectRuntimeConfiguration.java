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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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
    public DirectRouteSelector directRouteSelector(DirectRuntimeProperties properties, RuntimeSessionRouteMapper routes) {
        return new DirectRouteSelector(properties, routes);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService directTurnExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("direct-turn-", 0).factory());
    }

    @Bean
    public DirectChatGateway directChatGateway(RouteService routeService, RuntimeBindingService bindings,
                                               CubeSandboxClient cube, StreamRecordProcessor processor,
                                               OutputStreamManager outputStreamManager,
                                               RunningOutputStreamRegistry runningRegistry,
                                               DirectRuntimeProperties properties, ExecutorService directTurnExecutor) {
        int port = properties.getRuntime().getPort();
        DirectChatGateway.TurnTransport transport = (runtime, turn) -> {
            Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port,
                    DirectProtocol.ROUTE_PREFIX + "/turns")
                .header(DirectProtocol.ADMISSION_HEADER, turn.admission())
                .header(DirectProtocol.SIGNATURE_HEADER, turn.signature())
                .post(RequestBody.create(turn.body(), MediaType.get("application/json")))
                .build();
            Response response = cube.stream(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                int code = response.code();
                response.close();
                throw new IOException("Direct runtime refused the admitted turn: HTTP " + code);
            }
            return new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
        };
        return new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager, runningRegistry,
            properties, directTurnExecutor, Clock.systemUTC());
    }

    static Optional<JSONObject> probeStatus(CubeSandboxClient cube, RuntimeBinding binding, String secret, int port) {
        String path = DirectProtocol.ROUTE_PREFIX + "/status";
        Request request = cube.sandboxRequest(binding.getSandboxId(), binding.getTrafficToken(), port, path)
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
