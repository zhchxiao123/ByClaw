package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.state.common.exception.BdpRuntimeException;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.enums.ChatTransport;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.manager.domain.tenant.TenantRequestContext;

class DirectRouteSelectorTest {

    private DirectRuntimeProperties properties;
    private RuntimeSessionRouteMapper routes;
    private DirectRouteSelector selector;
    private LoginInfo login;

    @BeforeEach
    void setUp() {
        properties = new DirectRuntimeProperties();
        properties.setEnabled(true);
        properties.getCanaryUserCodes().add("u1");
        routes = mock(RuntimeSessionRouteMapper.class);
        selector = new DirectRouteSelector(properties, routes);
        login = new LoginInfo();
        login.setUserId(100L);
        login.setUserCode("u1");
    }

    private ChatProcessContext context(boolean created, ChatTransport transport, String workerType) {
        AssistantChatDto dto = new AssistantChatDto();
        dto.setServerCreatedSession(created);
        ChatProcessContext ctx = new ChatProcessContext(new ByteArrayOutputStream(), dto);
        ctx.setSessionId(9000L);
        ctx.transport = transport;
        ctx.setParams(new HashMap<>(Map.of("worker_agent_type", workerType)));
        return ctx;
    }

    @Test
    void aNewCanaryHarnessSessionIsRecordedAsDirect() {
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "HARNESS"), login))
            .contains(new RuntimeOwnerScope(100L, "u1", 0L));
        verify(routes).insert(9000L, "u100.e0", DirectRouteSelector.DIRECT);
    }

    @Test
    void whileAdmissionIsClosedNewSessionsUseLegacyAndDirectSessionsStayDirect() {
        selector.withAdmission(() -> false);
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "HARNESS"), login)).isEmpty();
        verify(routes, never()).insert(anyLong(), anyString(), anyString());

        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn("u100.e0");
        assertThat(selector.select(context(false, ChatTransport.WEBSOCKET, "HARNESS"), login))
            .as("a Direct session is never handed to the Worker").isPresent();
    }

    @Test
    void theFirstTurnOfASessionCreatedByAnUploadBecomesDirect() {
        ChatProcessContext uploaded = context(false, ChatTransport.WEBSOCKET, "HARNESS");
        uploaded.setUserMessageId(9100L);
        when(routes.countMessagesBefore(9000L, 9100L)).thenReturn(0L);
        assertThat(selector.select(uploaded, login)).isPresent();
        verify(routes).insert(9000L, "u100.e0", DirectRouteSelector.DIRECT);
    }

    @Test
    void aSessionWithEarlierTurnsAndNoDirectRouteStaysLegacy() {
        ChatProcessContext used = context(false, ChatTransport.WEBSOCKET, "HARNESS");
        used.setUserMessageId(9100L);
        when(routes.countMessagesBefore(9000L, 9100L)).thenReturn(2L);
        assertThat(selector.select(used, login)).isEmpty();
        verify(routes, never()).insert(anyLong(), anyString(), anyString());
    }

    @Test
    void existingLegacySessionsKeepTheirRoute() {
        assertThat(selector.select(context(false, ChatTransport.WEBSOCKET, "HARNESS"), login)).isEmpty();
        when(routes.selectBackend(9000L)).thenReturn("legacy");
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "HARNESS"), login)).isEmpty();
        verify(routes, never()).insert(anyLong(), anyString(), anyString());
    }

    @Test
    void aRecordedDirectSessionNeverFallsBackToTheWorker() {
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn("u100.e0");
        properties.setEnabled(false);
        properties.getCanaryUserCodes().clear();
        assertThat(selector.select(context(false, ChatTransport.WEBSOCKET, "HARNESS"), login)).isPresent();
    }

    @Test
    void anotherOwnerCannotUseADirectSession() {
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn("u200.e0");
        assertThatThrownBy(() -> selector.select(context(false, ChatTransport.WEBSOCKET, "HARNESS"), login))
            .isInstanceOf(BdpRuntimeException.class).hasMessage("无权访问该会话");
        assertThatThrownBy(() -> selector.requireOwnerIfDirect(9000L, login))
            .as("the session guard runs before any message is written")
            .isInstanceOf(BdpRuntimeException.class).hasMessage("无权访问该会话");
    }

    @Test
    void theSessionGuardIgnoresLegacySessionsAndAdmitsTheOwner() {
        assertThat(selector.requireOwnerIfDirect(9000L, login)).isNull();
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn("u100.e0");
        assertThat(selector.requireOwnerIfDirect(9000L, login)).isEqualTo(new RuntimeOwnerScope(100L, "u1", 0L));
    }

    @Test
    void projectSessionsStayOnLegacyBecauseTheirWorkspaceIsSharedStorage() {
        ChatProcessContext project = context(true, ChatTransport.WEBSOCKET, "HARNESS");
        project.getAssistantChatDto().setProjectId(42L);
        assertThat(selector.select(project, login)).isEmpty();
        ChatProcessContext unscoped = context(true, ChatTransport.WEBSOCKET, "HARNESS");
        unscoped.getAssistantChatDto().setProjectId(-1L);
        assertThat(selector.select(unscoped, login)).as("no project").isPresent();
        assertThat(selector.selectForServerCreatedSession(9001L, "HARNESS", 42L, login)).isEmpty();
    }

    @Test
    void ineligibleTurnsStayOnLegacy() {
        properties.getCanaryUserCodes().clear();
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "HARNESS"), login)).as("not canary").isEmpty();
        properties.getCanaryUserCodes().add("u1");
        assertThat(selector.select(context(true, ChatTransport.HTTP_SSE, "HARNESS"), login)).as("SSE").isEmpty();
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "BYCLAW_EXE"), login)).as("worker").isEmpty();
        ChatProcessContext tenant = context(true, ChatTransport.WEBSOCKET, "HARNESS");
        tenant.tenantContext = mock(TenantRequestContext.class);
        assertThat(selector.select(tenant, login)).as("tenant").isEmpty();
        ChatProcessContext group = context(true, ChatTransport.WEBSOCKET, "HARNESS");
        group.getAssistantChatDto().setExtParams(new HashMap<>(Map.of("groupCoordination", Map.of("x", 1))));
        assertThat(selector.select(group, login)).as("group").isEmpty();
        properties.setEnabled(false);
        assertThat(selector.select(context(true, ChatTransport.WEBSOCKET, "HARNESS"), login)).as("disabled").isEmpty();
        verify(routes, never()).insert(anyLong(), anyString(), anyString());
    }
}
