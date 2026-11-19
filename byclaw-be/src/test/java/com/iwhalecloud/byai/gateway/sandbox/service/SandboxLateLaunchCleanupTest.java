package com.iwhalecloud.byai.gateway.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A launch whose write-back finds its row moved on (released, or taken over after the launch lock lapsed) may only
 * clean up what is still its own: a successor row can run on the same remote instance or still be starting.
 */
class SandboxLateLaunchCleanupTest extends com.iwhalecloud.byai.common.i18n.I18nTestSupport {
    private final SandboxService service = spy(new SandboxService());
    private final SsSandboxRecordMapper mapper = mock(SsSandboxRecordMapper.class);
    private final SandboxLifecycleFacade facade = mock(SandboxLifecycleFacade.class);
    private final SandboxMetadataCache cache = mock(SandboxMetadataCache.class);
    private final SandboxLaunchContextFactory contexts = mock(SandboxLaunchContextFactory.class);
    private final MockedStatic<RedisUtil> redis = mockStatic(RedisUtil.class);
    private final MockedStatic<ServiceRegistryCleanup> registry = mockStatic(ServiceRegistryCleanup.class);

    SandboxLateLaunchCleanupTest() {
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
        SandboxLaunchData data = new SandboxLaunchData();
        data.setSandboxId("remote-1");
        data.setEndpoint("http://host/proxy/18789/chat");
        data.setGatewayToken("token");
        when(facade.launchSandbox(any())).thenReturn(SandboxResponse.success(data));
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

    private SandboxLaunchData launch() {
        return ReflectionTestUtils.invokeMethod(service, "doLaunchSandbox", "user001", -1L,
            new SandboxLaunchRouting("openclaw", -1L));
    }

    private void currentRow(Long id, String status, String sandboxId) {
        SsSandboxRecord current = new SsSandboxRecord();
        current.setId(id);
        current.setUserCode("user001");
        current.setSandboxType("openclaw");
        current.setResourceId(-1L);
        current.setStatus(status);
        current.setSandboxId(sandboxId);
        when(mapper.selectActiveByUserAndResource("user001", "openclaw", -1L)).thenReturn(current);
    }

    @Test
    void aSuccessorRunningOnTheSameInstanceKeepsItAndItsRoute() {
        currentRow(100L, "RUNNING", "remote-1");
        assertThat(launch()).isNull();
        verify(facade, never()).removeSandbox(any());
        verify(cache, never()).evict(any(), any());
        registry.verifyNoInteractions();
    }

    @Test
    void aSuccessorStillStartingMayAdoptTheInstanceSoItIsKept() {
        currentRow(100L, "STARTING", null);
        assertThat(launch()).isNull();
        verify(facade, never()).removeSandbox(any());
        verify(cache, never()).evict(any(), any());
    }

    @Test
    void aSuccessorOnAnotherInstanceKeepsItsRouteWhileTheLateInstanceGoes() {
        currentRow(100L, "RUNNING", "remote-2");
        assertThat(launch()).isNull();
        verify(facade).removeSandbox(argThat(info -> "remote-1".equals(info.getSandboxId())));
        verify(cache, never()).evict(any(), any());
        registry.verifyNoInteractions();
    }

    @Test
    void withoutASuccessorTheLateInstanceAndItsRouteAreCleanedUpAsBefore() {
        when(mapper.selectActiveByUserAndResource("user001", "openclaw", -1L)).thenReturn(null);
        assertThat(launch()).isNull();
        verify(facade).removeSandbox(argThat(info -> "remote-1".equals(info.getSandboxId())));
        verify(cache).evict("user001", "openclaw");
    }

    @Test
    void itsOwnRowBeingReleasedStillCleansUpAsBefore() {
        currentRow(99L, "RELEASING", null);
        assertThat(launch()).isNull();
        verify(facade).removeSandbox(argThat(info -> "remote-1".equals(info.getSandboxId())));
        verify(cache).evict("user001", "openclaw");
    }
}
