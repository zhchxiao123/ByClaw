package com.iwhalecloud.byai.state.domain.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.iwhalecloud.byai.common.exception.BaseException;
import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.common.message.entity.ByaiMessageHotDto;
import com.iwhalecloud.byai.manager.mapper.message.MessageShareLinkMapper;
import com.iwhalecloud.byai.manager.mapper.message.MessageShareLinkMessageMapper;
import com.iwhalecloud.byai.state.application.service.message.MessageService;
import com.iwhalecloud.byai.state.application.service.session.SessionReadAccess;
import com.iwhalecloud.byai.state.domain.sys.service.SequenceService;

class MessageShareServiceAccessTest {

    private final MessageShareLinkMapper links = mock(MessageShareLinkMapper.class);
    private final MessageShareLinkMessageMapper linkMessages = mock(MessageShareLinkMessageMapper.class);
    private final MessageService messages = mock(MessageService.class);
    private final SessionReadAccess sessions = mock(SessionReadAccess.class);
    private final MessageShareService service = new MessageShareService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "messageShareLinkMapper", links);
        ReflectionTestUtils.setField(service, "messageShareLinkMessageMapper", linkMessages);
        ReflectionTestUtils.setField(service, "sequenceService", mock(SequenceService.class));
        ReflectionTestUtils.setField(service, "messageService", messages);
        ReflectionTestUtils.setField(service, "sessionReadAccess", sessions);
        when(sessions.readable(anyList(), any())).thenAnswer(call -> {
            List<ByaiMessageHotDto> found = call.getArgument(0);
            return found.stream().filter(m -> m.getSessionId() == 10L).toList();
        });
        when(links.insert(any())).thenReturn(1);
        when(linkMessages.insertBatch(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
        LoginInfo login = new LoginInfo();
        login.setUserId(7L);
        CurrentUserHolder.setLoginInfo(login);
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.clearLoginInfo();
    }

    @Test
    void sharesMessagesTheCreatorMayRead() {
        when(messages.getMessageByIds(any())).thenReturn(List.of(message(1L, 10L), message(2L, 10L)));

        assertThat(service.createShareLink(List.of(1L, 2L), 7, null, null, "t")).isNotBlank();
    }

    @Test
    void refusesAMessageOfAnotherOwnersSession() {
        when(messages.getMessageByIds(any())).thenReturn(List.of(message(1L, 10L), message(3L, 11L)));

        assertThatThrownBy(() -> service.createShareLink(List.of(1L, 3L), 7, null, null, "t"))
            .isInstanceOf(BaseException.class);
        verify(links, never()).insert(any());
    }

    @Test
    void refusesAnUnknownMessageId() {
        when(messages.getMessageByIds(any())).thenReturn(List.of(message(1L, 10L)));

        assertThatThrownBy(() -> service.createShareLink(List.of(1L, 404L), 7, null, null, "t"))
            .isInstanceOf(BaseException.class);
        verify(links, never()).insert(any());
    }

    private static ByaiMessageHotDto message(Long id, Long sessionId) {
        ByaiMessageHotDto message = new ByaiMessageHotDto();
        message.setMessageId(id);
        message.setSessionId(sessionId);
        return message;
    }
}
