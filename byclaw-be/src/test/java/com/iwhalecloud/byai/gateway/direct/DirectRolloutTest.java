package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.state.domain.chat.service.StreamDispatchResult;
import com.iwhalecloud.byai.state.domain.chat.service.StreamRecordProcessor;

class DirectRolloutTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RuntimeSessionRouteMapper routes = mock(RuntimeSessionRouteMapper.class);
    private final StreamRecordProcessor processor = mock(StreamRecordProcessor.class);
    private final DirectRollout rollout;

    @SuppressWarnings("unchecked")
    DirectRolloutTest() {
        when(redis.opsForValue()).thenReturn(values);
        ObjectProvider<StreamRecordProcessor> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(processor);
        ObjectProvider<com.iwhalecloud.byai.state.domain.chat.service.OutputStreamManager> streams = mock(ObjectProvider.class);
        ObjectProvider<com.iwhalecloud.byai.state.domain.chat.service.RunningOutputStreamRegistry> running = mock(ObjectProvider.class);
        rollout = new DirectRollout(redis, routes, provider, streams, running, Runnable::run);
    }

    @Test
    void admissionIsOpenUntilAnOperatorClosesItForEveryBackend() {
        assertThat(rollout.admissionOpen()).isTrue();
        rollout.setAdmission(false);
        verify(values).set(DirectRollout.ADMISSION_KEY, DirectRollout.CLOSED);
        when(values.get(DirectRollout.ADMISSION_KEY)).thenReturn(DirectRollout.CLOSED);
        assertThat(rollout.admissionOpen()).isFalse();
        rollout.setAdmission(true);
        verify(redis).delete(DirectRollout.ADMISSION_KEY);
    }

    @Test
    void anUnreadableAdmissionStateIsTreatedAsClosed() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertThat(rollout.admissionOpen()).isFalse();
    }

    @Test
    void onlyRecordedDirectSessionsAreDirect() {
        when(routes.selectBackend(1L)).thenReturn(DirectRouteSelector.DIRECT);
        when(routes.selectBackend(2L)).thenReturn("legacy");
        assertThat(rollout.recordedDirect(1L)).isTrue();
        assertThat(rollout.recordedDirect(2L)).isFalse();
        assertThat(rollout.recordedDirect(3L)).isFalse();
        assertThat(rollout.recordedDirect(null)).isFalse();
    }

    @Test
    void aTurnEndsOnItsOwnAnswerWithTheReason() {
        when(processor.processEvent(org.mockito.ArgumentMatchers.any(), anyString(), eq("direct")))
            .thenReturn(StreamDispatchResult.terminalHandled(null));
        ChatProcessContext ctx = new ChatProcessContext(null, new com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto());
        ctx.sessionId = 9000L;
        ctx.traceId = "9001_9002";
        ctx.modelAnswerMessageId = 9002L;

        rollout.endWithoutRuntime(ctx, "已停用");

        ArgumentCaptor<JSONObject> event = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(event.capture(), anyString(), eq("direct"));
        assertThat(event.getValue().getString("event_type")).isEqualTo("error");
        assertThat(event.getValue().getString("message_id")).isEqualTo("9002");
        assertThat(event.getValue().getString("trace_id")).isEqualTo("9001_9002");
        assertThat(event.getValue().getJSONObject("metadata").getString("error")).isEqualTo("已停用");
        verify(processor).afterAcknowledge(org.mockito.ArgumentMatchers.any());
    }
}
