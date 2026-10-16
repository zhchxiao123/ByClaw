package com.iwhalecloud.byai.state.application.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.DirectRouteSelector;
import com.iwhalecloud.byai.gateway.direct.RuntimeOwnerScope;
import com.iwhalecloud.byai.manager.entity.session.ByaiSession;
import com.iwhalecloud.byai.state.application.service.chat.ChatFileArtifactApplicationService;
import com.iwhalecloud.byai.state.common.exception.BdpRuntimeException;
import com.iwhalecloud.byai.state.domain.session.service.SessionMemberService;
import com.iwhalecloud.byai.state.domain.session.service.SessionService;

class SessionReadAccessTest {

    private final SessionService sessions = mock(SessionService.class);
    private final SessionMemberService members = mock(SessionMemberService.class);
    private final ChatFileArtifactApplicationService files = mock(ChatFileArtifactApplicationService.class);
    private final DirectRouteSelector routes = mock(DirectRouteSelector.class);
    private SessionReadAccess access;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ChatFileArtifactApplicationService> fileProvider = mock(ObjectProvider.class);
        when(fileProvider.getObject()).thenReturn(files);
        ObjectProvider<DirectRouteSelector> routeProvider = mock(ObjectProvider.class);
        when(routeProvider.getIfAvailable()).thenReturn(routes);
        access = new SessionReadAccess(sessions, members, fileProvider, routeProvider);
        when(sessions.findById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(new ByaiSession());
        LoginInfo login = new LoginInfo();
        login.setUserId(7L);
        login.setUserCode("xiaofeng");
        CurrentUserHolder.setLoginInfo(login);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clearLoginInfo();
    }

    @Test
    void directSessionIsReadableOnlyByItsOwnerScope() {
        when(routes.isDirect(10L)).thenReturn(true);
        when(routes.requireOwnerIfDirect(eq(10L), any())).thenThrow(new BdpRuntimeException("denied"));
        when(routes.isDirect(11L)).thenReturn(true);
        when(routes.requireOwnerIfDirect(eq(11L), any())).thenReturn(new RuntimeOwnerScope(7L, "xiaofeng", 1L));

        assertThat(access.canRead(10L)).isFalse();
        assertThat(access.canRead(11L)).isTrue();
        verify(files, never()).canView(any());
    }

    @Test
    void legacySessionKeepsThePlatformViewRule() {
        when(files.canView(20L)).thenReturn(true);
        when(files.canView(21L)).thenReturn(false);

        assertThat(access.canRead(20L)).isTrue();
        assertThat(access.canRead(21L)).isFalse();
    }

    @Test
    void aSessionNotStoredYetIsNotARefusal() {
        when(sessions.findById(50L)).thenReturn(null);
        assertThat(access.canRead(50L)).isTrue();
        verify(files, never()).canView(50L);
    }

    @Test
    void nothingIsReadableWithoutALogin() {
        CurrentUserHolder.clearLoginInfo();
        when(files.canView(20L)).thenReturn(true);

        assertThat(access.canRead(20L)).isFalse();
        assertThat(access.canRead(null)).isFalse();
    }

    @Test
    void writingNeedsReadAndTheSessionWriteRule() {
        when(files.canView(30L)).thenReturn(true);
        ByaiSession own = new ByaiSession();
        own.setSessionId(30L);
        own.setCreatorId(7L);
        when(sessions.findById(30L)).thenReturn(own);
        when(files.canView(31L)).thenReturn(true);
        ByaiSession others = new ByaiSession();
        others.setSessionId(31L);
        others.setCreatorId(8L);
        when(sessions.findById(31L)).thenReturn(others);

        assertThat(access.canWrite(30L)).isTrue();
        assertThat(access.canWrite(31L)).isFalse();
    }

    @Test
    void readableKeepsOnlyMessagesOfReadableSessionsAndAsksOncePerSession() {
        when(files.canView(40L)).thenReturn(true);
        when(files.canView(41L)).thenReturn(false);

        List<Long> kept = access.readable(List.of(40L, 41L, 40L, 40L), id -> id);

        assertThat(kept).containsExactly(40L, 40L, 40L);
        verify(files).canView(40L);
    }
}
