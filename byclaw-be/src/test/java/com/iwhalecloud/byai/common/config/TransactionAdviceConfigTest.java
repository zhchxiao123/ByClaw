package com.iwhalecloud.byai.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import javax.sql.DataSource;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.state.domain.chat.service.SessionRuntimeStateService;
import com.iwhalecloud.byai.state.domain.chat.service.ScopedSessionEventService;
import com.iwhalecloud.byai.state.domain.chat.service.RunningChatSnapshotService;
import com.iwhalecloud.byai.state.domain.chat.service.CronService;
import com.iwhalecloud.byai.state.domain.ws.service.MultiDeviceBroadcastService;
import com.iwhalecloud.byai.state.domain.message.service.MemoryMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class TransactionAdviceConfigTest extends com.iwhalecloud.byai.common.i18n.I18nTestSupport {

    @Test
    void jwtSigningVerificationAndRefreshDoNotBorrowConnections() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", new DataSourceTransactionManager(dataSource));
        var keys = java.security.KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var target = new com.iwhalecloud.byai.common.jwt.JwtService();
        ReflectionTestUtils.setField(target, "privateKey", pair.getPrivate());
        ReflectionTestUtils.setField(target, "jwtParser", io.jsonwebtoken.Jwts.parserBuilder()
            .setSigningKey(pair.getPublic()).build());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(config.getAdvisor());
        var service = (com.iwhalecloud.byai.common.jwt.JwtService) factory.getProxy();
        var payload = java.util.Map.of("userCode", "test-user");
        String token = service.createJwt(payload, 1, java.util.concurrent.TimeUnit.HOURS);
        assertThat(service.verifyJwt(token, com.alibaba.fastjson.JSONObject.class).getString("userCode"))
            .isEqualTo("test-user");
        service.createJwt(payload);
        service.generateRefreshJwt(payload);
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertThat(config.getAdvisor().getTransactionAttributeSource()
                    .getTransactionAttribute(method, target.getClass()).getPropagationBehavior())
                    .as(method.toString()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            }
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void jdkFeignProxyMethodsDoNotBorrowConnections() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", new DataSourceTransactionManager(dataSource));
        Class<?> type = com.iwhalecloud.byai.common.feign.client.FeignWhaleAgentService.class;
        Object target = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> null);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(config.getAdvisor());
        Object service = factory.getProxy();
        for (Method method : type.getDeclaredMethods()) {
            method.invoke(service, new Object[method.getParameterCount()]);
        }
        verifyNoInteractions(dataSource);
    }

    @Test
    void tenantProviderObservationDoesNotJoinOrPoisonTheCallerTransaction() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:mem:tenantObservation;DB_CLOSE_DELAY=-1", "sa", "");
        var manager = new DataSourceTransactionManager(ds);
        var config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", manager);
        var client = mock(com.iwhalecloud.byai.gateway.sandbox.client.OpenSandboxClient.class);
        org.mockito.Mockito.when(client.getSandboxIfExists("tenant-id")).thenAnswer(inv -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // Downstream failure is contained by providerStatus, even within a real caller transaction.
            new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                throw new IllegalStateException("provider unavailable");
            });
            return null;
        });
        var service = new com.iwhalecloud.byai.gateway.sandbox.service.TenantSandboxService(
            mock(com.iwhalecloud.byai.gateway.sandbox.service.SandboxLifecycleFacade.class), client,
            mock(com.iwhalecloud.byai.gateway.sandbox.spec.SandboxServiceSpecRepository.class),
            mock(com.iwhalecloud.byai.manager.mapper.sandbox.SsSandboxRecordMapper.class),
            new com.fasterxml.jackson.databind.ObjectMapper(), "/tmp", "redis", "6379", "0",
            "default", "", "http://be", "", "host");
        var factory = new ProxyFactory(service);
        factory.addAdvice(config.getAdvisor());
        var proxy = (com.iwhalecloud.byai.gateway.sandbox.service.TenantSandboxService) factory.getProxy();
        var record = new com.iwhalecloud.byai.manager.entity.sandbox.SsSandboxRecord();
        record.setSandboxId("tenant-id");
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
            assertThat(proxy.providerStatus(record)).isEqualTo("UNKNOWN");
            assertThat(status.isRollbackOnly()).isFalse();
            return null;
        });
    }

    @Test
    void forwardingTransportSuspendsAnActualCallerTransaction() {
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:forwardAdvice;DB_CLOSE_DELAY=-1","sa","");
        var manager=new DataSourceTransactionManager(ds);var config=new TransactionAdviceConfig();ReflectionTestUtils.setField(config,"transactionManager",manager);
        var node=mock(com.iwhalecloud.byai.manager.domain.tenant.TenantNodeClient.class);
        org.mockito.Mockito.when(node.request(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("GET"),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.any())).thenAnswer(inv->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new com.iwhalecloud.byai.state.domain.groupchat.forward.domain.ForwardModels.ForwardOperation(3,com.iwhalecloud.byai.state.domain.groupchat.forward.domain.ForwardModels.ForwardOperationState.SUCCEEDED,java.util.List.of());
        });
        var target=new com.iwhalecloud.byai.manager.domain.tenant.TenantGroupForwardService(node,mock(com.iwhalecloud.byai.state.domain.groupchat.forward.application.ForwardPlatformPolicy.class),new com.iwhalecloud.byai.state.domain.groupchat.forward.config.GroupChatForwardProperties());
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(config.getAdvisor());var proxy=(com.iwhalecloud.byai.manager.domain.tenant.TenantGroupForwardService)factory.getProxy();
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();proxy.status(new com.iwhalecloud.byai.state.domain.groupchat.forward.domain.ForwardModels.ForwardActor(1,2),4,3);assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null;});
    }

    @Test
    void startupWaitsAndLaunchesDoNotTakeTheirOwnTransaction() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        var source = config.getAdvisor().getTransactionAttributeSource();
        var route = com.iwhalecloud.byai.gateway.route.RouteService.class;
        var readiness = com.iwhalecloud.byai.gateway.route.WorkerRouteReadinessService.class;
        var sandbox = com.iwhalecloud.byai.gateway.sandbox.service.SandboxService.class;
        var context = com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext.class;
        assertThat(propagation(source, route.getMethod("route", context), route))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        for (Method method : readiness.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertThat(propagation(source, method, readiness)).as(method.toString())
                    .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            }
        }
        assertThat(propagation(source, sandbox.getMethod("waitWorkerReadySync", String.class, long.class), sandbox))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(source.getTransactionAttribute(sandbox.getMethod("launchSandboxForTargetWithoutWait",
            String.class, Long.class, String.class), sandbox)).isNull();
        // Matched by owner as well as name: the other route services and sandbox starts keep their transactions.
        var interfaceRoute = com.iwhalecloud.byai.gateway.route.InterfaceRouteService.class;
        assertThat(propagation(source, interfaceRoute.getMethod("route", context), interfaceRoute))
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(propagation(source, sandbox.getMethod("restartSandboxForTargetWithoutWait",
            String.class, Long.class, String.class), sandbox)).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void startingASandboxCostsTheChatThreadNoExtraConnectionAndTheStartupThreadNone() throws Exception {
        var counted = new CountingDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:mem:startupWait;DB_CLOSE_DELAY=-1", "sa", ""));
        var manager = new DataSourceTransactionManager(counted);
        var config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", manager);
        // Real classes behind the real transaction advice; the stubs only stand in for each method's body.
        var script = mock(com.iwhalecloud.byai.state.domain.chat.service.ScriptService.class);
        var route = mock(com.iwhalecloud.byai.gateway.route.RouteService.class);
        var readiness = mock(com.iwhalecloud.byai.gateway.route.WorkerRouteReadinessService.class);
        var sandbox = mock(com.iwhalecloud.byai.gateway.sandbox.service.SandboxService.class);
        var scriptProxy = advised(config, script);
        var routeProxy = advised(config, route);
        var readinessProxy = advised(config, readiness);
        var sandboxProxy = advised(config, sandbox);
        // MyBatis keeps the connection of a synchronized scope requested until the scope ends.
        Runnable mapperQuery = () -> DataSourceUtils.getConnection(counted);
        int[] held = {-1, -1, -1, -1};

        // Startup thread: the launch queries, then blocks on the platform; then it waits for the worker.
        org.mockito.Mockito.doAnswer(launch -> {
            new org.springframework.jdbc.core.JdbcTemplate(counted).queryForObject("select 1", Integer.class);
            held[2] = counted.open.get();
            return null;
        }).when(sandbox).launchSandboxForTargetWithoutWait(any(), any(), any());
        org.mockito.Mockito.doAnswer(waiting -> {
            held[3] = counted.open.get();
            return true;
        }).when(sandbox).waitWorkerReadySync(any(), org.mockito.ArgumentMatchers.anyLong());
        Thread startup = new Thread(() -> {
            sandboxProxy.launchSandboxForTargetWithoutWait("user", 1L, "target");
            sandboxProxy.waitWorkerReadySync("target", 1L);
        });
        startup.start();
        startup.join();
        assertThat(counted.open.get()).isZero();

        // Chat thread: the turn has queried once; routing and the readiness wait must not add a connection.
        org.mockito.Mockito.doAnswer(waiting -> {
            held[1] = counted.open.get();
            return null;
        }).when(readiness).ensureReady(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.doAnswer(routing -> {
            mapperQuery.run();
            readinessProxy.ensureReady("user", 1L, "target", phase -> { }, Long.MAX_VALUE);
            return null;
        }).when(route).route(any());
        org.mockito.Mockito.doAnswer(turn -> {
            mapperQuery.run();
            held[0] = counted.open.get();
            routeProxy.route(null);
            return null;
        }).when(script).executeAssistantChat(any(), any(), any());
        scriptProxy.executeAssistantChat(null, null, null);
        assertThat(counted.open.get()).isZero();

        assertThat(held).containsExactly(1, 1, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T advised(TransactionAdviceConfig config, T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(config.getAdvisor());
        return (T) factory.getProxy();
    }

    private static int propagation(TransactionAttributeSource source, Method method, Class<?> owner) {
        return source.getTransactionAttribute(method, owner).getPropagationBehavior();
    }

    /** Counts physical connections that are checked out and not yet closed. */
    private static final class CountingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final java.util.concurrent.atomic.AtomicInteger open = new java.util.concurrent.atomic.AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            java.sql.Connection connection = super.getConnection();
            open.incrementAndGet();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") && !connection.isClosed()) open.decrementAndGet();
                    try {
                        return method.invoke(connection, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        }
    }

    @Test
    void aChatTurnDoesNotHoldOneTransactionAcrossItsEntryMethods() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        var source = config.getAdvisor().getTransactionAttributeSource();
        var chat = com.iwhalecloud.byai.state.domain.chat.service.AssistantChatService.class;
        var dto = com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto.class;
        var out = java.io.OutputStream.class;
        assertThat(source.getTransactionAttribute(chat.getMethod("chat", dto, out,
            com.iwhalecloud.byai.common.login.bean.LoginInfo.class), chat).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var script = com.iwhalecloud.byai.state.domain.chat.service.ScriptService.class;
        assertThat(source.getTransactionAttribute(script.getMethod("executeAssistantChat", out, dto, Long.class), script)
            .getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var ws = com.iwhalecloud.byai.state.domain.ws.service.ChatService.class;
        Method llmChat = java.util.Arrays.stream(ws.getMethods()).filter(m -> m.getName().equals("llmChat")).findFirst().orElseThrow();
        assertThat(source.getTransactionAttribute(llmChat, ws).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        for (Class<?> channel : new Class<?>[] {
            com.iwhalecloud.byai.gateway.channels.service.app.AppChannelService.class,
            com.iwhalecloud.byai.gateway.channels.service.dingtalk.DingtalkChannelService.class,
            com.iwhalecloud.byai.gateway.channels.service.feishu.FeishuChannelService.class,
            com.iwhalecloud.byai.gateway.channels.service.web.WebChannelService.class,
            com.iwhalecloud.byai.gateway.channels.service.wecom.WecomChannelService.class}) {
            assertThat(source.getTransactionAttribute(channel.getMethod("chat", dto, out), channel).getPropagationBehavior())
                .as(channel.getSimpleName()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        }
        // The services the turn calls still commit their own writes, and same-named methods elsewhere keep theirs.
        assertThat(source.getTransactionAttribute(chat.getMethod("createGroupChatSession", dto), chat).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(resolvePropagation(source, "chat")).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(resolvePropagation(source, "llmChat")).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(resolvePropagation(source, "executeAssistantChat")).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void commonFileFallbackCannotMarkTheCallingChatRollbackOnly() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        var source = config.getAdvisor().getTransactionAttributeSource();
        var service = com.iwhalecloud.byai.manager.application.service.files.FilesApplicationService.class;
        Method read = service.getMethod("openCommonFileInputStream", String.class, String.class);
        assertThat(source.getTransactionAttribute(read, service).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(source.getTransactionAttribute(TxMethodSamples.class.getMethod("openCommonFileInputStream"),
            TxMethodSamples.class).getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void workspaceSkillCenterOwnsItsCommitBeforeFileCleanup() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        var source = config.getAdvisor().getTransactionAttributeSource();
        var service = com.iwhalecloud.byai.state.application.service.session.WorkspaceSkillCenterApplicationService.class;
        var request = com.iwhalecloud.byai.state.domain.resource.qo.WorkspaceSkillCenterQo.class;
        for (String method : new String[] {"preview", "sync"}) {
            assertThat(source.getTransactionAttribute(service.getMethod(method, request), service).getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        }
        assertThat(resolvePropagation(source, "createSomethingElse"))
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void publicationTransactionsDoNotChangeOrdinaryEmployeeSync() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        var source = config.getAdvisor().getTransactionAttributeSource();
        var coordinator = com.iwhalecloud.byai.manager.application.service.digitemploy.EmployeePublicationApplicationService.class;
        for (Method method : coordinator.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertThat(source.getTransactionAttribute(method, coordinator).getPropagationBehavior())
                    .as(method.getName()).isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            }
        }
        var employee = com.iwhalecloud.byai.manager.application.service.digitemploy.DigitalEmployeeApplicationService.class;
        var dto = com.iwhalecloud.byai.manager.dto.digitemploy.DigitalEmployeeDTO.class;
        assertThat(source.getTransactionAttribute(employee.getMethod("synOpenClawWorkSpace", Long.class, dto), employee).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(source.getTransactionAttribute(employee.getMethod("syncPublicationOpenClawWorkSpace", Long.class, dto), employee).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(source.getTransactionAttribute(employee.getMethod("restorePublicationRuntimeAfterRollback", Long.class), employee).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Test
    void streamClassificationDoesNotBorrowADatabaseConnection() {
        DataSource dataSource = mock(DataSource.class);
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", new DataSourceTransactionManager(dataSource));
        ProxyFactory factory = new ProxyFactory(new SessionRuntimeStateService());
        factory.addAdvice(config.getAdvisor());
        SessionRuntimeStateService service = (SessionRuntimeStateService) factory.getProxy();
        JSONObject event = JSONObject.parseObject("{\"metadata\":{\"event_kind\":\"session.runtime\","
            + "\"session_scope\":\"parent\"}}");

        assertThat(service.isRuntimeEvent(event)).isTrue();
        ProxyFactory cronFactory = new ProxyFactory(new CronService(null, null));
        cronFactory.addAdvice(config.getAdvisor());
        CronService cronService = (CronService) cronFactory.getProxy();
        assertThat(cronService.isCronChangedEvent("answerDelta")).isFalse();
        verifyNoInteractions(dataSource);
    }

    @Test
    void streamInfrastructureIsNonTransactionalWithoutChangingBusinessWrites() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        TransactionAttributeSource source = config.getAdvisor().getTransactionAttributeSource();
        for (Class<?> type : new Class<?>[] {SessionRuntimeStateService.class, ScopedSessionEventService.class,
                RunningChatSnapshotService.class, MultiDeviceBroadcastService.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    assertThat(source.getTransactionAttribute(method, type).getPropagationBehavior())
                        .as(type.getSimpleName() + "." + method.getName())
                        .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                }
            }
        }
        Method save = java.util.Arrays.stream(MemoryMessageService.class.getMethods())
            .filter(method -> method.getName().equals("save")).findFirst().orElseThrow();
        assertThat(source.getTransactionAttribute(save, MemoryMessageService.class).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(resolvePropagation(source, "createSomethingElse"))
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void defaultResourceInitializationMethodsDoNotJoinGlobalTransactions() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        TransactionInterceptor interceptor = config.getAdvisor();
        TransactionAttributeSource source = interceptor.getTransactionAttributeSource();

        assertThat(resolvePropagation(source, "createDatasetIfNotExists"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "createDefaultResourcesIfNotExists"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "syncResourceJsonByBizType"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "existsResourceJsonByBizType"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "upsertStandardJsonArtifact"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "prewarmDueCronSandboxes"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "callAsUser"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "runAsUser"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertThat(resolvePropagation(source, "createSomethingElse"))
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void saveAdapterDoesNotJoinTheGlobalDatabaseTransaction() throws Exception {
        TransactionAdviceConfig config = new TransactionAdviceConfig();
        ReflectionTestUtils.setField(config, "transactionManager", mock(DataSourceTransactionManager.class));
        TransactionInterceptor interceptor = config.getAdvisor();

        assertThat(resolvePropagation(interceptor.getTransactionAttributeSource(), "saveAdapter"))
            .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    private int resolvePropagation(TransactionAttributeSource source, String methodName) throws NoSuchMethodException {
        Method method = TxMethodSamples.class.getMethod(methodName);
        TransactionAttribute attribute = source.getTransactionAttribute(method, TxMethodSamples.class);
        assertThat(attribute).isNotNull();
        return attribute.getPropagationBehavior();
    }

    static class TxMethodSamples {

        public void chat() {
        }

        public void llmChat() {
        }

        public void executeAssistantChat() {
        }

        public void openCommonFileInputStream() {
        }

        public void createDatasetIfNotExists() {
        }

        public void createDefaultResourcesIfNotExists() {
        }

        public void syncResourceJsonByBizType() {
        }

        public void existsResourceJsonByBizType() {
        }

        public void upsertStandardJsonArtifact() {
        }

        public void prewarmDueCronSandboxes() {
        }

        public void callAsUser() {
        }

        public void runAsUser() {
        }

        public void createSomethingElse() {
        }

        public void saveAdapter() {
        }
    }
}
