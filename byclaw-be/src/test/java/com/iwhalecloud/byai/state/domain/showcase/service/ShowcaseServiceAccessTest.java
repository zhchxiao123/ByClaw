package com.iwhalecloud.byai.state.domain.showcase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.common.message.entity.ByaiMessageHotDto;
import com.iwhalecloud.byai.common.message.service.ByaiMessageHotService;
import com.iwhalecloud.byai.manager.entity.showcase.ByaiShowcase;
import com.iwhalecloud.byai.manager.mapper.showcase.ByaiShowcaseMapper;
import com.iwhalecloud.byai.state.application.service.session.SessionReadAccess;
import com.iwhalecloud.byai.state.domain.showcase.strategy.ShowcaseStrategyFactory;
import com.iwhalecloud.byai.state.domain.sys.service.SequenceService;
import com.iwhalecloud.byai.state.interfaces.controller.showcase.dto.ShowcaseCancelRequest;
import com.iwhalecloud.byai.state.interfaces.controller.showcase.dto.ShowcaseCreateRequest;

class ShowcaseServiceAccessTest {

    private final ByaiShowcaseMapper mapper = mock(ByaiShowcaseMapper.class);
    private final ByaiMessageHotService messages = mock(ByaiMessageHotService.class);
    private final SessionReadAccess sessions = mock(SessionReadAccess.class);
    private ShowcaseService service;

    @BeforeEach
    void setUp() {
        service = new ShowcaseService(mapper, mock(SequenceService.class), mock(ShowcaseStrategyFactory.class), messages);
        ReflectionTestUtils.setField(service, "sessionReadAccess", sessions);
        LoginInfo login = new LoginInfo();
        login.setUserId(7L);
        CurrentUserHolder.setLoginInfo(login);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clearLoginInfo();
    }

    @Test
    void onlyTheCreatorOwnsAShowcase() {
        when(mapper.selectById(1L)).thenReturn(showcase(1L, 7L));
        when(mapper.selectById(2L)).thenReturn(showcase(2L, 8L));

        assertThat(service.ownedByCurrentUser(1L)).isTrue();
        assertThat(service.ownedByCurrentUser(2L)).isFalse();
        assertThat(service.ownedByCurrentUser(3L)).isFalse();
        assertThat(service.ownedByCurrentUser(null)).isFalse();
    }

    @Test
    void collectingNeedsReadOfTheSessionAndOfEveryCollectedMessage() {
        when(sessions.canRead(10L)).thenReturn(true);
        when(sessions.canRead(11L)).thenReturn(false);
        when(messages.findById(100L)).thenReturn(message(100L, 10L));
        when(messages.findById(101L)).thenReturn(message(101L, 11L));

        assertThat(service.collectable(create(10L, 100L, "chat", "100"))).isTrue();
        assertThat(service.collectable(create(11L, null, "file", null))).isFalse();
        assertThat(service.collectable(create(10L, 101L, "file", null))).as("a foreign message").isFalse();
        assertThat(service.collectable(create(10L, 100L, "chat", "100,101"))).as("a foreign id in the content").isFalse();
        assertThat(service.collectable(create(10L, 100L, "chat", "100,999"))).as("an unknown id").isFalse();
        assertThat(service.collectable(create(10L, 100L, "chat", "100,x"))).as("a malformed id").isFalse();
    }

    @Test
    void cancellingTouchesOnlyTheCallersOwnShowcases() {
        ShowcaseCancelRequest request = new ShowcaseCancelRequest();
        request.setSessionId(10L);
        request.setType("chat");
        request.setMessageId(100L);

        service.cancelCollect(request);

        verify(mapper).updateStatusByCondition(eq(10L), eq("chat"), any(), eq(100L), eq(0), any(), eq(7L));
    }

    private static ByaiShowcase showcase(Long id, Long creator) {
        ByaiShowcase showcase = new ByaiShowcase();
        showcase.setId(id);
        showcase.setCreateBy(creator);
        return showcase;
    }

    private static ByaiMessageHotDto message(Long id, Long sessionId) {
        ByaiMessageHotDto message = new ByaiMessageHotDto();
        message.setMessageId(id);
        message.setSessionId(sessionId);
        return message;
    }

    private static ShowcaseCreateRequest create(Long sessionId, Long messageId, String type, String content) {
        ShowcaseCreateRequest request = new ShowcaseCreateRequest();
        request.setSessionId(sessionId);
        request.setMessageId(messageId);
        request.setType(type);
        request.setContent(content);
        return request;
    }
}
