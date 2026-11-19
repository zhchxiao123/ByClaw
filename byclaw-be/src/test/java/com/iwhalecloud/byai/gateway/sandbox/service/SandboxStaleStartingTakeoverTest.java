package com.iwhalecloud.byai.gateway.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iwhalecloud.byai.common.feign.response.SandboxResponse;
import com.iwhalecloud.byai.common.feign.response.sandbox.SandboxLaunchData;
import com.iwhalecloud.byai.common.util.RedisUtil;
import com.iwhalecloud.byai.manager.application.service.login.LoginApplicationService;
import com.iwhalecloud.byai.manager.entity.sandbox.SsSandboxRecord;
import com.iwhalecloud.byai.manager.mapper.sandbox.SsSandboxRecordMapper;
import com.iwhalecloud.byai.state.domain.sys.service.ByaiSystemConfigService;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A request that finds a stale STARTING row (its first launch outlived the launch lock) takes the row over under its
 * own launch lock. The takeover must keep that lock and must never remove a sandbox the original attempt finished.
 */
class SandboxStaleStartingTakeoverTest extends com.iwhalecloud.byai.common.i18n.I18nTestSupport {
    private final SandboxService service = spy(new SandboxService());
    private final SsSandboxRecordMapper mapper = mock(SsSandboxRecordMapper.class);
    private final SandboxLifecycleFacade facade = mock(SandboxLifecycleFacade.class);
    private final SandboxMetadataCache cache = mock(SandboxMetadataCache.class);
    private final SandboxLaunchContextFactory contexts = mock(SandboxLaunchContextFactory.class);
    private final MockedStatic<RedisUtil> redis = mockStatic(RedisUtil.class);
    private final SsSandboxRecord stale = new SsSandboxRecord();

    SandboxStaleStartingTakeoverTest() {
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
        redis.when(() -> RedisUtil.lock(anyString(), anyString(), anyLong())).thenReturn(true);
        stale.setId(7L);
        stale.setUserCode("user001");
        stale.setResourceId(-1L);
        stale.setSandboxType("openclaw");
        stale.setStatus("STARTING");
        stale.setLockVersion(3);
        stale.setVersion(3);
        stale.setCreateTime(new Date(0));
        stale.setUpdateTime(new Date(0));
        when(mapper.selectActiveByUserAndResource("user001", "openclaw", -1L)).thenReturn(stale);
    }

    @AfterEach
    void close() {
        redis.close();
    }

    private SandboxLaunchData launch() {
        return ReflectionTestUtils.invokeMethod(service, "launchSandboxInternal", "user001", -1L,
            new SandboxLaunchRouting("openclaw", -1L));
    }

    @Test
    void theTakeoverKeepsTheLaunchLockItHoldsThroughTheRelaunch() {
        when(mapper.markStartingReleased(eq(7L), eq("release.remote.missing"), any(), eq(3))).thenReturn(1);
        doAnswer(call -> {
            ((SsSandboxRecord) call.getArgument(0)).setId(8L);
            return 1;
        }).when(mapper).insert(any());
        when(facade.launchSandbox(any())).thenAnswer(call -> {
            // The relaunch runs with this request's lock still in place.
            redis.verify(() -> RedisUtil.del(anyString()), never());
            redis.verify(() -> RedisUtil.releaseLock(anyString(), anyString()), never());
            SandboxLaunchData data = new SandboxLaunchData();
            data.setSandboxId("remote-new");
            data.setEndpoint("http://host/proxy/18789/chat");
            data.setGatewayToken("token");
            return SandboxResponse.success(data);
        });
        when(mapper.updateLaunchSuccess(eq(8L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0)))
            .thenReturn(1);

        assertThat(launch().getSandboxId()).isEqualTo("remote-new");
        verify(mapper).markStartingReleased(eq(7L), eq("release.remote.missing"), any(), eq(3));
        redis.verify(() -> RedisUtil.del(anyString()), never());
        redis.verify(() -> RedisUtil.releaseLock(eq("sandbox:launch:lock:user001:openclaw:-1"), anyString()));
    }

    @Test
    void aRowTheOriginalAttemptFinishedMeanwhileIsReusedNotRemoved() {
        when(mapper.markStartingReleased(any(), any(), any(), any())).thenReturn(0);
        SsSandboxRecord running = new SsSandboxRecord();
        running.setId(7L);
        running.setUserCode("user001");
        running.setResourceId(-1L);
        running.setSandboxType("openclaw");
        running.setStatus("RUNNING");
        running.setSandboxId("remote-original");
        running.setEndpoint("http://host/proxy/18789/chat");
        running.setGatewayToken("token");
        when(mapper.selectById(7L)).thenReturn(running);

        assertThat(launch().getSandboxId()).isEqualTo("remote-original");
        verify(facade, never()).launchSandbox(any());
        verify(facade, never()).removeSandbox(any());
        redis.verify(() -> RedisUtil.del(anyString()), never());
    }

    @Test
    void aRowThatMovedOnToAnythingElseStaysBusy() {
        when(mapper.markStartingReleased(any(), any(), any(), any())).thenReturn(0);
        SsSandboxRecord failed = new SsSandboxRecord();
        failed.setId(7L);
        failed.setStatus("FAILED");
        when(mapper.selectById(7L)).thenReturn(failed);

        assertThatThrownBy(this::launch).hasMessageContaining(
            com.iwhalecloud.byai.common.i18n.I18nUtil.get("sandbox.launch.busy"));
        verify(facade, never()).launchSandbox(any());
        verify(facade, never()).removeSandbox(any());
    }
}
