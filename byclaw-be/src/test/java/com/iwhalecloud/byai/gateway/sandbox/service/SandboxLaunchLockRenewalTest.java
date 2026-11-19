package com.iwhalecloud.byai.gateway.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iwhalecloud.byai.common.discovery.ServiceRegistryCleanup;
import com.iwhalecloud.byai.common.feign.response.SandboxResponse;
import com.iwhalecloud.byai.common.feign.response.sandbox.SandboxLaunchData;
import com.iwhalecloud.byai.common.util.RedisUtil;
import com.iwhalecloud.byai.manager.application.service.login.LoginApplicationService;
import com.iwhalecloud.byai.manager.entity.sandbox.SsSandboxRecord;
import com.iwhalecloud.byai.manager.mapper.sandbox.SsSandboxRecordMapper;
import com.iwhalecloud.byai.state.domain.sys.service.ByaiSystemConfigService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The launch lock is renewed while a launch runs, so a live launch cannot be taken over. A launch whose write-back
 * finds its row moved on removes its instance only while it still holds that lock; once the lock lapsed, a later
 * request may have taken the row over and run on that very instance, so it leaves instance, cache and route alone.
 */
class SandboxLaunchLockRenewalTest extends com.iwhalecloud.byai.common.i18n.I18nTestSupport {
    private final SandboxService service = spy(new SandboxService());
    private final SsSandboxRecordMapper mapper = mock(SsSandboxRecordMapper.class);
    private final SandboxLifecycleFacade facade = mock(SandboxLifecycleFacade.class);
    private final SandboxMetadataCache cache = mock(SandboxMetadataCache.class);
    private final SandboxLaunchContextFactory contexts = mock(SandboxLaunchContextFactory.class);
    private final MockedStatic<RedisUtil> redis = mockStatic(RedisUtil.class);
    private final MockedStatic<ServiceRegistryCleanup> registry = mockStatic(ServiceRegistryCleanup.class);
    private final AtomicInteger renewals = new AtomicInteger();
    private final AtomicBoolean lockHeld = new AtomicBoolean(true);

    SandboxLaunchLockRenewalTest() {
        ReflectionTestUtils.setField(service, "sandboxRecordMapper", mapper);
        ReflectionTestUtils.setField(service, "sandboxTransactionManager",
            mock(org.springframework.jdbc.datasource.DataSourceTransactionManager.class));
        ReflectionTestUtils.setField(service, "sandboxLifecycleFacade", facade);
        ReflectionTestUtils.setField(service, "sandboxMetadataCache", cache);
        ReflectionTestUtils.setField(service, "sandboxLaunchContextFactory", contexts);
        ReflectionTestUtils.setField(service, "sandboxUserContextRunner",
            new SandboxUserContextRunner(mock(LoginApplicationService.class)));
        ReflectionTestUtils.setField(service, "byaiSystemConfigService", mock(ByaiSystemConfigService.class));
        ReflectionTestUtils.setField(service, "launchLeaseRenewInterval", Duration.ofMillis(10));
        ReflectionTestUtils.setField(service, "launchLockRenewer", (SandboxLaunchLease.Renewer) (key, token, ttl) -> {
            assertThat(key).isEqualTo("sandbox:launch:lock:user001:openclaw:-1");
            renewals.incrementAndGet();
            return lockHeld.get();
        });
        when(contexts.buildContext("user001", -1L, "openclaw"))
            .thenReturn(new SandboxLaunchContext("openclaw", Map.of(), Map.of(), "token"));
        redis.when(() -> RedisUtil.lock(anyString(), anyString(), anyLong())).thenReturn(true);
        doAnswer(call -> {
            ((SsSandboxRecord) call.getArgument(0)).setId(99L);
            return 1;
        }).when(mapper).insert(any());
        when(facade.removeSandbox(any())).thenReturn(SandboxResponse.success(null));
        // The write-back loses: the row moved on while the remote call ran.
        when(mapper.updateLaunchSuccess(eq(99L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0)))
            .thenReturn(0);
    }

    @AfterEach
    void close() {
        redis.close();
        registry.close();
    }

    private void remoteCallTaking(long millis, Runnable during) {
        when(facade.launchSandbox(any())).thenAnswer(call -> {
            Thread.sleep(millis);
            during.run();
            SandboxLaunchData data = new SandboxLaunchData();
            data.setSandboxId("remote-1");
            data.setEndpoint("http://host/proxy/18789/chat");
            data.setGatewayToken("token");
            return SandboxResponse.success(data);
        });
    }

    private SandboxLaunchData launch() {
        return ReflectionTestUtils.invokeMethod(service, "launchSandboxInternal", "user001", -1L,
            new SandboxLaunchRouting("openclaw", -1L));
    }

    @Test
    void theLockIsRenewedWhileTheRemoteCallRunsAndReleasedByTokenAfter() {
        remoteCallTaking(120, () -> assertThat(renewals.get()).isGreaterThanOrEqualTo(3));
        launch();
        redis.verify(() -> RedisUtil.releaseLock(eq("sandbox:launch:lock:user001:openclaw:-1"), anyString()));
        int afterRelease = renewals.get();
        try {
            Thread.sleep(60);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertThat(renewals.get()).isEqualTo(afterRelease);
    }

    @Test
    void stillHoldingTheLockALateLaunchRemovesItsInstanceAsBefore() {
        // E.g. the user stopped the sandbox while it was starting: the row was released, the lock is still ours.
        remoteCallTaking(30, () -> { });
        assertThat(launch()).isNull();
        verify(facade).removeSandbox(argThat(info -> "remote-1".equals(info.getSandboxId())));
        verify(cache).evict("user001", "openclaw");
    }

    @Test
    void afterLosingTheLockALateLaunchLeavesInstanceCacheAndRouteAlone() {
        // The lock lapsed during the remote call; a later request may now run on this very instance.
        remoteCallTaking(60, () -> lockHeld.set(false));
        assertThat(launch()).isNull();
        verify(facade, never()).removeSandbox(any());
        verify(cache, never()).evict(any(), any());
        registry.verifyNoInteractions();
    }
}
