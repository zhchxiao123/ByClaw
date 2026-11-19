package com.iwhalecloud.byai.gateway.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iwhalecloud.byai.common.concurrent.SandboxReconcileCancellation;
import com.iwhalecloud.byai.common.feign.response.SandboxResponse;
import com.iwhalecloud.byai.common.feign.response.sandbox.SandboxLaunchData;
import com.iwhalecloud.byai.common.util.RedisUtil;
import com.iwhalecloud.byai.manager.application.service.login.LoginApplicationService;
import com.iwhalecloud.byai.manager.entity.sandbox.SsSandboxRecord;
import com.iwhalecloud.byai.manager.mapper.sandbox.SsSandboxRecordMapper;
import com.iwhalecloud.byai.state.domain.sys.service.ByaiSystemConfigService;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The legacy launch commits its STARTING row before the remote call, with no transaction around both. A launch
 * that fails before its result is written must release that row, or every retry answers sandbox.launch.busy.
 */
class SandboxLaunchFailureReleaseTest extends com.iwhalecloud.byai.common.i18n.I18nTestSupport {
    private final SandboxService service = spy(new SandboxService());
    private final SsSandboxRecordMapper mapper = mock(SsSandboxRecordMapper.class);
    private final SandboxLifecycleFacade facade = mock(SandboxLifecycleFacade.class);
    private final SandboxMetadataCache cache = mock(SandboxMetadataCache.class);
    private final SandboxLaunchContextFactory contexts = mock(SandboxLaunchContextFactory.class);
    private final MockedStatic<RedisUtil> redis = mockStatic(RedisUtil.class);

    SandboxLaunchFailureReleaseTest() {
        ReflectionTestUtils.setField(service, "sandboxRecordMapper", mapper);
        ReflectionTestUtils.setField(service, "sandboxTransactionManager",
            mock(org.springframework.jdbc.datasource.DataSourceTransactionManager.class));
        ReflectionTestUtils.setField(service, "sandboxLifecycleFacade", facade);
        ReflectionTestUtils.setField(service, "sandboxMetadataCache", cache);
        ReflectionTestUtils.setField(service, "sandboxLaunchContextFactory", contexts);
        ReflectionTestUtils.setField(service, "sandboxUserContextRunner",
            new SandboxUserContextRunner(mock(LoginApplicationService.class)));
        ReflectionTestUtils.setField(service, "byaiSystemConfigService", mock(ByaiSystemConfigService.class));
        when(contexts.buildContext("user001", -1L, "openclaw"))
            .thenReturn(new SandboxLaunchContext("openclaw", Map.of(), Map.of(), "token"));
        doAnswer(call -> {
            ((SsSandboxRecord) call.getArgument(0)).setId(99L);
            return 1;
        }).when(mapper).insert(any());
    }

    @AfterEach
    void close() {
        redis.close();
        SandboxReconcileCancellation.clear();
    }

    private SandboxLaunchData launch() {
        return ReflectionTestUtils.invokeMethod(service, "doLaunchSandbox", "user001", -1L,
            new SandboxLaunchRouting("openclaw", -1L));
    }

    private static SandboxResponse<SandboxLaunchData> launched() {
        SandboxLaunchData data = new SandboxLaunchData();
        data.setSandboxId("remote-new");
        data.setEndpoint("http://host/proxy/18789/chat");
        data.setGatewayToken("token");
        return SandboxResponse.success(data);
    }

    @Test
    void aFailedStatusWriteBackReleasesTheStartingRow() {
        when(facade.launchSandbox(any())).thenReturn(launched());
        var writeBack = new TransientDataAccessResourceException("connection reset");
        when(mapper.updateLaunchSuccess(eq(99L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0)))
            .thenThrow(writeBack);
        when(mapper.markStartingReleased(eq(99L), eq("release.launch.failed"), any(), eq(0))).thenReturn(1);

        assertThatThrownBy(this::launch).isSameAs(writeBack);
        verify(mapper).markStartingReleased(eq(99L), eq("release.launch.failed"), any(), eq(0));
    }

    @Test
    void theReleaseLeavesTheLaunchLockCacheAndEndpointToWhoeverHoldsThemNow() {
        // The caller's lock may have lapsed and a retry may already hold it, with its cache entry and endpoint.
        when(facade.launchSandbox(any())).thenReturn(launched());
        when(mapper.updateLaunchSuccess(eq(99L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0)))
            .thenThrow(new TransientDataAccessResourceException("connection reset"));
        when(mapper.markStartingReleased(eq(99L), eq("release.launch.failed"), any(), eq(0))).thenReturn(1);
        try (var registry = mockStatic(com.iwhalecloud.byai.common.discovery.ServiceRegistryCleanup.class)) {
            assertThatThrownBy(this::launch).isInstanceOf(TransientDataAccessResourceException.class);
            registry.verifyNoInteractions();
        }
        redis.verify(() -> RedisUtil.del(org.mockito.ArgumentMatchers.anyString()), never());
        redis.verify(() -> RedisUtil.releaseLock(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()), never());
        verify(cache, never()).evict(any(), any());
    }

    @Test
    void aFailedRemoteLaunchReleasesTheStartingRow() {
        var remote = new IllegalStateException("lifecycle unavailable");
        when(facade.launchSandbox(any())).thenThrow(remote);
        when(mapper.markStartingReleased(eq(99L), eq("release.launch.failed"), any(), eq(0))).thenReturn(1);

        assertThatThrownBy(this::launch).isSameAs(remote);
        verify(mapper).markStartingReleased(eq(99L), eq("release.launch.failed"), any(), eq(0));
    }

    @Test
    void aRowTheWriteBackAlreadyMovedOnIsLeftAlone() {
        when(facade.launchSandbox(any())).thenReturn(launched());
        when(mapper.updateLaunchSuccess(eq(99L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0)))
            .thenThrow(new TransientDataAccessResourceException("commit outcome unknown"));
        when(mapper.markStartingReleased(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(this::launch).isInstanceOf(TransientDataAccessResourceException.class);
        // No fallback to the running-release path: a RUNNING row is a usable sandbox.
        verify(mapper, never()).selectById(any());
        verify(facade, never()).removeSandbox(any());
    }

    @Test
    void aFailedReleaseKeepsTheLaunchFailure() {
        var remote = new IllegalStateException("lifecycle unavailable");
        when(facade.launchSandbox(any())).thenThrow(remote);
        var release = new TransientDataAccessResourceException("database unavailable");
        when(mapper.markStartingReleased(any(), any(), any(), any())).thenThrow(release);

        assertThatThrownBy(this::launch).isSameAs(remote);
        assertThat(remote.getSuppressed()).containsExactly(release);
    }

    @Test
    void reconcileStillKeepsItsStartingRowForRecovery() {
        SandboxReconcileCancellation.setDeadline(Long.MAX_VALUE);
        when(facade.launchSandbox(any())).thenThrow(new IllegalStateException("lifecycle unavailable"));

        assertThatThrownBy(this::launch).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).markStartingReleased(any(), any(), any(), any());
    }
}
