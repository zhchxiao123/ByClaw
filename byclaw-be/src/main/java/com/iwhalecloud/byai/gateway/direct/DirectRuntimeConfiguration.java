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

import org.springframework.beans.factory.annotation.Value;
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
import com.iwhalecloud.byai.common.constants.users.UserState;
import com.iwhalecloud.byai.common.storage.UserFS;
import com.iwhalecloud.byai.common.storage.impl.LocalStorageService;
import com.iwhalecloud.byai.gateway.direct.mapper.ProjectionProgressMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RequestAdmissionMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeBindingMapper;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;
import com.iwhalecloud.byai.gateway.route.RouteService;
import com.iwhalecloud.byai.manager.domain.aimodel.service.AiModelService;
import com.iwhalecloud.byai.manager.domain.enterprise.service.TenantUserMembershipService;
import com.iwhalecloud.byai.manager.domain.users.service.UserService;
import com.iwhalecloud.byai.manager.entity.users.Users;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
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

    private static final java.util.regex.Pattern OWNER_KEY = java.util.regex.Pattern.compile("u(\\d+)\\.e(\\d+)");
    private static final long DEFAULT_ENTERPRISE_ID = 1L;
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
                                                       SequenceService sequenceService,
                                                       @Value("${langfuse.base-url:}") String langfuseUrl,
                                                       @Value("${langfuse.public-key:}") String langfusePublicKey,
                                                       @Value("${langfuse.secret-key:}") String langfuseSecretKey,
                                                       @Value("${spring.application.name:ByaiService}") String serviceName,
                                                       DirectResourceScope directResourceScope) {
        reportUsageLikeThePlatform(properties.getRuntime(), langfuseUrl, langfusePublicKey, langfuseSecretKey);
        giveToolsDiscoveryOnly(properties, redis, serviceName);
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
        RuntimeBindingService.RuntimeControl control = new RuntimeBindingService.RuntimeControl() {
            @Override
            public int drain(RuntimeBinding binding, String secret) {
                return controlCall(cube, binding, secret, port, "/drain");
            }

            @Override
            public void undrain(RuntimeBinding binding, String secret) {
                controlCall(cube, binding, secret, port, "/undrain");
            }
        };
        return new RuntimeBindingService(properties, mapper, cube, lock, probe,
            new OwnerStateDirectories(properties.getRuntime()), sequenceService::nextVal, Clock.systemUTC())
            .withControl(control).withResourceScope(directResourceScope);
    }

    /** Pauses runtimes that stayed idle past the configured time. */
    @Bean
    public DirectIdleController directIdleController(RuntimeBindingService directRuntimeBindings,
                                                     RuntimeBindingMapper bindingMapper,
                                                     DirectTransactions directTransactions,
                                                     RuntimeScopeAuthority directScopeAuthority,
                                                     DirectChatGateway directChatGateway,
                                                     DirectResourceScope directResourceScope) {
        return new DirectIdleController(directRuntimeBindings,
            () -> directTransactions.requiresNew(bindingMapper::selectReadyOwners))
            .withRevocation(directScopeAuthority, directChatGateway::stopOwnerWork)
            .withGrantRefresh(directResourceScope::refresh);
    }

    /** Enterprise-scoped copies of the user's grant hash, one per Direct owner. */
    @Bean
    public DirectResourceScope directResourceScope(org.springframework.data.redis.core.StringRedisTemplate redis,
                                                   com.iwhalecloud.byai.gateway.direct.mapper.ResourceEnterpriseMapper resources,
                                                   DirectTransactions directTransactions) {
        DirectResourceScope.Store store = new DirectResourceScope.Store() {
            @Override
            public Map<String, String> read(String key) {
                Map<String, String> entries = new java.util.LinkedHashMap<>();
                redis.opsForHash().entries(key).forEach((field, value) -> entries.put(String.valueOf(field), String.valueOf(value)));
                return entries;
            }

            @Override
            public void replace(String key, Map<String, String> entries) {
                // Write aside, then rename over the copy: the runtime never reads a half-written hash.
                String staging = key + ":next";
                redis.delete(staging);
                redis.opsForHash().putAll(staging, entries);
                redis.rename(staging, key);
            }
        };
        return new DirectResourceScope(store, ids -> {
            java.util.List<Long> numeric = ids.stream().filter(id -> id.matches("\\d{1,18}")).map(Long::valueOf).toList();
            Map<String, Long> found = new java.util.HashMap<>();
            if (numeric.isEmpty()) return found;
            for (Map<String, Object> row : directTransactions.requiresNew(() -> resources.enterprises(numeric))) {
                Object enterprise = row.get("enterpriseId");
                if (enterprise instanceof Number number) found.put(String.valueOf(row.get("resourceId")), number.longValue());
            }
            return found;
        });
    }

    /**
     * Tools find platform services through the discovery registry with a read-only Redis user
     * that sees only discovery keys; the shared Redis password stays out of the tool environment.
     */
    static void giveToolsDiscoveryOnly(DirectRuntimeProperties properties, RedisTemplate<String, Object> redis,
                                       String serviceName) {
        String user = properties.getRuntime().getToolRedisUser();
        if (org.apache.commons.lang3.StringUtils.isBlank(user)) return;
        DirectToolRedis access = new DirectToolRedis(properties.getMasterSecret(), user, serviceName, command ->
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> connection.execute(
                command[0], java.util.Arrays.stream(command, 1, command.length)
                    .map(part -> part.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new))));
        try {
            access.define();
        }
        catch (RuntimeException e) {
            log.warn("Direct tool Redis user not defined; runtime tools cannot use service discovery: {}",
                e.getClass().getSimpleName());
            return;
        }
        access.toolEnvironment().forEach(properties.getRuntime().getEnv()::putIfAbsent);
    }

    /**
     * Monthly quota counts the usage the platform reads from Langfuse by user code; a Direct runtime
     * reports its model usage there too, so quota applies as for every other runtime. Explicit
     * runtime settings win.
     */
    static void reportUsageLikeThePlatform(DirectRuntimeProperties.Runtime runtime, String baseUrl, String publicKey,
                                           String secretKey) {
        if (org.apache.commons.lang3.StringUtils.isAnyBlank(baseUrl, publicKey, secretKey)) return;
        runtime.getEnv().putIfAbsent("LANGFUSE_BASE_URL", baseUrl);
        runtime.getEnv().putIfAbsent("LANGFUSE_PUBLIC_KEY", publicKey);
        runtime.getSecretEnv().putIfAbsent("LANGFUSE_SECRET_KEY", secretKey);
    }

    /** The user must be enabled and, outside the default enterprise, an active member. */
    @Bean
    public RuntimeScopeAuthority directScopeAuthority(UserService userService,
                                                      TenantUserMembershipService tenantUserMembershipService,
                                                      DirectTransactions directTransactions) {
        return ownerKey -> directTransactions.requiresNew(() -> {
            java.util.regex.Matcher key = OWNER_KEY.matcher(ownerKey);
            if (!key.matches()) return "owner_unknown";
            long userId = Long.parseLong(key.group(1));
            long enterpriseId = Long.parseLong(key.group(2));
            Users user = userService.findById(userId);
            if (user == null) return "user_missing";
            if (UserState.DISABLED.equalsIgnoreCase(user.getState())) return "user_disabled";
            if (enterpriseId != DEFAULT_ENTERPRISE_ID
                && tenantUserMembershipService.findActiveByUserIdAndEnterpriseId(userId, enterpriseId) == null) {
                return "membership_inactive";
            }
            return null;
        });
    }

    static int controlCall(CubeSandboxClient cube, RuntimeBinding binding, String secret, int port, String action) {
        String path = DirectProtocol.ROUTE_PREFIX + action;
        Request request = cube.sandboxRequest(binding.getSandboxId(), binding.getTrafficToken(), port, path)
            .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(secret, "POST", path, System.currentTimeMillis()))
            .post(RequestBody.create("{}", MediaType.get("application/json")))
            .build();
        try (Response response = cube.call(request).execute()) {
            return response.code();
        }
        catch (IOException e) {
            return 0;
        }
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
    public DirectTurnGate directTurnGate(DirectRouteSelector routes, DirectAdmissions admissions,
                                         RuntimeScopeAuthority directScopeAuthority) {
        return new DirectTurnGate(routes, admissions).withAuthority(directScopeAuthority);
    }

    @Bean
    public DirectChatGateway directChatGateway(RouteService routeService, RuntimeBindingService bindings,
                                               CubeSandboxClient cube, StreamRecordProcessor processor,
                                               OutputStreamManager outputStreamManager,
                                               RunningOutputStreamRegistry runningRegistry, DirectAdmissions admissions,
                                               DirectRuntimeProperties properties, ExecutorService directTurnExecutor,
                                               ProjectionProgressMapper progressMapper,
                                               RuntimeBindingMapper bindingMapper,
                                               DirectTransactions directTransactions,
                                               RuntimeScopeAuthority directScopeAuthority,
                                               DirectAttachments directAttachments) {
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
            public JSONObject control(RuntimeBindingService.ReadyRuntime runtime, long sessionId, String action,
                                      String requestId) throws IOException {
                String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/control";
                JSONObject payload = new JSONObject();
                payload.put("action", action);
                if (requestId != null) payload.put("requestId", requestId);
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), "POST",
                        path, System.currentTimeMillis()))
                    .post(RequestBody.create(payload.toJSONString(), MediaType.get("application/json")))
                    .build();
                try (Response response = cube.call(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Direct control failed: HTTP " + response.code());
                    }
                    return JSON.parseObject(response.body().string());
                }
            }

            @Override
            public JSONObject answerInteraction(RuntimeBindingService.ReadyRuntime runtime, long sessionId,
                                                String interactionId, JSONObject body) throws IOException {
                String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/interactions/" + interactionId
                    + "/answer";
                Request request = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), "POST",
                        path, System.currentTimeMillis()))
                    .post(RequestBody.create(body.toJSONString(), MediaType.get("application/json")))
                    .build();
                try (Response response = cube.call(request).execute()) {
                    // 404/409 carry the interaction's settled state; anything else is a delivery failure.
                    if (response.body() == null || (!response.isSuccessful() && response.code() != 404 && response.code() != 409)) {
                        throw new IOException("Direct interaction answer failed: HTTP " + response.code());
                    }
                    JSONObject result = JSON.parseObject(response.body().string());
                    result.put("settledNow", response.isSuccessful());
                    return result;
                }
            }

            @Override
            public JSONObject sessionResource(RuntimeBindingService.ReadyRuntime runtime, String method, String path)
                throws IOException {
                Request.Builder builder = cube.sandboxRequest(runtime.sandboxId(), runtime.trafficToken(), port, path)
                    .header(DirectProtocol.CONTROL_HEADER, DirectProtocol.controlHeader(runtime.runtimeSecret(), method,
                        path, System.currentTimeMillis()));
                Request request = "POST".equals(method)
                    ? builder.post(RequestBody.create("{}", MediaType.get("application/json"))).build() : builder.get().build();
                try (Response response = cube.call(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Direct session resource HTTP " + response.code());
                    }
                    return JSON.parseObject(response.body().string());
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
            .withProjectionProgress(progress).withAuthority(directScopeAuthority).withAttachments(directAttachments);
    }

    @Bean
    public DirectDesktop directDesktop(RuntimeBindingService directRuntimeBindings, CubeSandboxClient cube,
                                       RuntimeScopeAuthority directScopeAuthority, DirectRuntimeProperties properties) {
        DirectDesktop desktop = new DirectDesktop(directRuntimeBindings, cube, directScopeAuthority,
            properties.getRuntime().getDesktopPort(), properties.getMasterSecret(), Clock.systemUTC());
        directRuntimeBindings.withActivity(desktop::inUse);
        return desktop;
    }

    /**
     * The desktop viewer's stream: matched only for WebSocket upgrades on the desktop prefix, so
     * its HTTP pages and assets reach {@link DirectDesktopController}.
     */
    @Bean
    public org.springframework.web.servlet.handler.SimpleUrlHandlerMapping directDesktopWebSocketMapping(DirectDesktop directDesktop) {
        DirectDesktopWebSocketHandler handler = new DirectDesktopWebSocketHandler(directDesktop);
        org.springframework.web.socket.server.support.WebSocketHttpRequestHandler upgrade =
            new org.springframework.web.socket.server.support.WebSocketHttpRequestHandler(handler,
                new org.springframework.web.socket.server.support.DefaultHandshakeHandler());
        upgrade.setHandshakeInterceptors(java.util.List.of(handler));
        org.springframework.web.servlet.handler.SimpleUrlHandlerMapping mapping =
            new org.springframework.web.servlet.handler.SimpleUrlHandlerMapping() {
                @Override
                protected Object getHandlerInternal(jakarta.servlet.http.HttpServletRequest request) throws Exception {
                    String upgradeHeader = request.getHeader("Upgrade");
                    return upgradeHeader != null && "websocket".equalsIgnoreCase(upgradeHeader.trim())
                        ? super.getHandlerInternal(request) : null;
                }
            };
        mapping.setOrder(-2);
        mapping.setUrlMap(Map.of(DirectDesktopController.PREFIX + "/**",
            (org.springframework.web.HttpRequestHandler) upgrade::handleRequest));
        return mapping;
    }

    /** Direct sessions' files, on the host side of each owner's state mount; links never lead out of it. */
    @Bean
    public DirectSessionFiles directSessionFiles(DirectRouteSelector directRouteSelector, DirectRuntimeProperties properties,
                                                 RuntimeBindingService directRuntimeBindings) {
        OwnerStateDirectories directories = new OwnerStateDirectories(properties.getRuntime());
        return new DirectSessionFiles(directRouteSelector,
            LocalStorageService.confined(properties.getRuntime().getLocalMountRoot()),
            owner -> directRuntimeBindings.find(owner).map(binding -> directories.holds(owner, binding.getBindingId()))
                .orElse(false));
    }

    @Bean
    public DirectAttachments directAttachments(DirectSessionFiles directSessionFiles, UserFS userFS,
                                               AiModelService aiModelService) {
        return new DirectAttachments(directSessionFiles, userFS::read, dto -> acceptsImages(aiModelService, dto));
    }

    /** Model ability "7" is image input; without a resolved model nothing is assumed. */
    static boolean acceptsImages(AiModelService models, AssistantChatDto dto) {
        var selection = dto == null ? null : dto.getSessionModelSelection();
        var model = selection == null ? null : models.getModel(selection.getModelId());
        Object abilities = model == null || model.getInstanceParam() == null ? null : model.getInstanceParam().get("abilities");
        return abilities instanceof java.util.Collection<?> list && list.stream().anyMatch(item -> "7".equals(String.valueOf(item)));
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

    /**
     * Owner directories with a marker naming the binding whose state they hold. A missing or empty
     * directory, or one claimed by another binding, does not hold the owner's state. A non-empty
     * directory from before markers existed is adopted once.
     */
    static final class OwnerStateDirectories implements RuntimeBindingService.OwnerDirectories {
        static final String MARKER = ".byclaw-owner";
        private final DirectRuntimeProperties.Runtime runtime;

        OwnerStateDirectories(DirectRuntimeProperties.Runtime runtime) {
            this.runtime = runtime;
        }

        @Override
        public String prepare(RuntimeOwnerScope owner) {
            return prepareOwnerDirectory(runtime, owner);
        }

        @Override
        public void claim(RuntimeOwnerScope owner, long bindingId) {
            Path marker = local(owner).resolve(MARKER);
            try {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
                Files.writeString(marker, expected(owner, bindingId), StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("r--r--r--"));
            }
            catch (IOException e) {
                throw new IllegalStateException("Direct owner state marker could not be written", e);
            }
        }

        @Override
        public boolean holds(RuntimeOwnerScope owner, long bindingId) {
            Path local = local(owner);
            Path marker = local.resolve(MARKER);
            try {
                if (!Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)) return false;
                if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    return expected(owner, bindingId).equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
                }
                try (var entries = Files.list(local)) {
                    if (entries.findAny().isEmpty()) return false;
                }
                claim(owner, bindingId);
                return true;
            }
            catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private Path local(RuntimeOwnerScope owner) {
            return Path.of(runtime.getLocalMountRoot()).resolve(owner.directoryName());
        }

        private static String expected(RuntimeOwnerScope owner, long bindingId) {
            return owner.key() + ":" + bindingId;
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
