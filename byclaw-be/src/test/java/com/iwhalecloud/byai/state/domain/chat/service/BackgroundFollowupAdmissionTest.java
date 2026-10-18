package com.iwhalecloud.byai.state.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.iwhalecloud.byai.gateway.direct.DirectRouteSelector;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.dto.RunningChatInfo;

class BackgroundFollowupAdmissionTest {
    @Test
    void idleRootAcceptsNewMessageWhileBackgroundAgentsKeepWorking() {
        RunningChatInfo running = running(false, true);
        ChatProcessContext ctx = context(null);
        assertThatCode(() -> admit(ctx, running)).doesNotThrowAnyException();
        assertThat(ctx.concurrentGatewayTurn).isTrue();
        assertThat(ctx.continueRunningTrace).isFalse();
        assertThat(ctx.sendByFrameworkMsgOnly).isFalse();
        assertThat(ctx.modelAnswerMessageId).isNull();
    }

    @Test
    void activeCaptainAndUnknownInputStateStillRejectIndependentRequests() {
        assertThatThrownBy(() -> admit(context(null), running(true, false)))
            .hasMessageContaining("当前会话仍在运行中");
        assertThatThrownBy(() -> admit(context(null), running(false, null)))
            .hasMessageContaining("当前会话仍在运行中");
    }

    @Test
    void aDirectSessionTakesANewMessageWhileItsAnswerRuns() {
        ChatProcessContext ctx = context(null);
        assertThatCode(() -> admit(ctx, running(true, false), true)).doesNotThrowAnyException();
        assertThat(ctx.concurrentGatewayTurn).isTrue();
        assertThat(ctx.continueRunningTrace).isFalse();
        assertThat(ctx.modelAnswerMessageId).isNull();
    }

    @Test
    void matchingTraceStillUsesTheExistingInteractionContinuation() {
        ChatProcessContext ctx = context("original");
        admit(ctx, running(false, true));
        assertThat(ctx.continueRunningTrace).isTrue();
        assertThat(ctx.sendByFrameworkMsgOnly).isTrue();
        assertThat(ctx.modelAnswerMessageId).isEqualTo(12L);
    }

    private ChatProcessContext context(String trace) {
        AssistantChatDto dto = new AssistantChatDto();
        dto.setTraceId(trace);
        ChatProcessContext ctx = new ChatProcessContext(null, dto);
        ctx.sessionId = 10L;
        return ctx;
    }

    private RunningChatInfo running(Boolean rootActive, Boolean acceptingInput) {
        RunningChatInfo running = new RunningChatInfo();
        running.setRunning(true);
        running.setRuntimeSource("test-runtime");
        running.setRootActive(rootActive);
        running.setAcceptingInput(acceptingInput);
        running.setActiveChildCount(4L);
        running.setTraceId("original");
        running.setUserMessageId(11L);
        running.setModelAnswerMessageId(12L);
        return running;
    }

    private void admit(ChatProcessContext ctx, RunningChatInfo running) {
        admit(ctx, running, false);
    }

    @SuppressWarnings("unchecked")
    private void admit(ChatProcessContext ctx, RunningChatInfo running, boolean direct) {
        ScriptService service = new ScriptService();
        DirectRouteSelector selector = mock(DirectRouteSelector.class);
        when(selector.isDirect(10L)).thenReturn(direct);
        ObjectProvider<DirectRouteSelector> selectors = mock(ObjectProvider.class);
        when(selectors.getIfAvailable()).thenReturn(selector);
        ReflectionTestUtils.setField(service, "directRouteSelector", selectors);
        RunningOutputStreamRegistry registry = mock(RunningOutputStreamRegistry.class);
        ReflectionTestUtils.setField(service, "runningOutputStreamRegistry", registry);
        when(registry.getRunning(10L)).thenReturn(running);
        ReflectionTestUtils.invokeMethod(service, "resolveRunningTraceState", ctx);
    }
}
