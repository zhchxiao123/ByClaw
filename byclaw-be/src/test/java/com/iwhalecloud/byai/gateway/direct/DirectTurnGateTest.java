package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;

class DirectTurnGateTest {

    private RuntimeSessionRouteMapper routes;
    private InMemoryRequestAdmissions rows;
    private DirectTurnGate gate;
    private LoginInfo login;

    @BeforeEach
    void setUp() {
        routes = mock(RuntimeSessionRouteMapper.class);
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectOwner(9000L)).thenReturn("u100.e0");
        rows = new InMemoryRequestAdmissions();
        gate = new DirectTurnGate(new DirectRouteSelector(new DirectRuntimeProperties(), routes), new DirectAdmissions(rows));
        login = new LoginInfo();
        login.setUserId(100L);
        login.setUserCode("u1");
    }

    private AssistantChatDto dto(String text) {
        AssistantChatDto dto = new AssistantChatDto();
        dto.setSessionId(9000L);
        dto.setChatContent(text);
        dto.setExtParams(new HashMap<>(Map.of("requestId", "req-1")));
        return dto;
    }

    @Test
    void aRepeatedRequestReportsItsAdmissionInsteadOfRunning() {
        AssistantChatDto first = dto("hello");
        assertThat(gate.admit(first, new ByteArrayOutputStream(), login)).isTrue();
        assertThat(first.getDirectReservedRequestId()).isEqualTo("req-1");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(gate.admit(dto("hello"), out, login)).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("尚未确认").contains("req-1");

        ByteArrayOutputStream conflict = new ByteArrayOutputStream();
        assertThat(gate.admit(dto("changed"), conflict, login)).isFalse();
        assertThat(conflict.toString(StandardCharsets.UTF_8)).contains("不同的内容").contains("conflict");
    }

    @Test
    void legacySessionsAndRequestsWithoutABusinessIdPassThrough() {
        when(routes.selectBackend(9000L)).thenReturn(null);
        assertThat(gate.admit(dto("hello"), new ByteArrayOutputStream(), login)).isTrue();
        assertThat(rows.rows).isEmpty();
        when(routes.selectBackend(9000L)).thenReturn(DirectRouteSelector.DIRECT);
        AssistantChatDto none = dto("hello");
        none.getExtParams().remove("requestId");
        assertThat(gate.admit(none, new ByteArrayOutputStream(), login)).isTrue();
        assertThat(rows.rows).isEmpty();
    }
}
