package com.iwhalecloud.byai.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.RuntimeUnavailableException;
import com.iwhalecloud.byai.gateway.route.PreparedChatGatewayRequest;
import com.iwhalecloud.byai.gateway.route.RouteService;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.state.domain.chat.service.OutputStreamManager;
import com.iwhalecloud.byai.state.domain.chat.service.RunningOutputStreamRegistry;
import com.iwhalecloud.byai.state.domain.chat.service.StreamDispatchResult;
import com.iwhalecloud.byai.state.domain.chat.service.StreamRecordProcessor;

class DirectChatGatewayTest {

    private static final RuntimeOwnerScope OWNER = new RuntimeOwnerScope(100L, "u1", 0L);
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private RouteService routeService;
    private RuntimeBindingService bindings;
    private StreamRecordProcessor processor;
    private OutputStreamManager outputStreamManager;
    private RunningOutputStreamRegistry registry;
    private final List<String> opened = new ArrayList<>();
    private String runtimeOutput;
    private IOException transportFailure;
    private DirectChatGateway gateway;
    private ChatProcessContext ctx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        routeService = mock(RouteService.class);
        bindings = mock(RuntimeBindingService.class);
        processor = mock(StreamRecordProcessor.class);
        outputStreamManager = mock(OutputStreamManager.class);
        registry = mock(RunningOutputStreamRegistry.class);
        when(outputStreamManager.withSessionLock(anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        ctx = new ChatProcessContext(new ByteArrayOutputStream(), new AssistantChatDto());
        ctx.setSessionId(9000L);
        ctx.setTraceId("9001_9002");
        when(routeService.prepareDirectGatewayRequest(ctx)).thenReturn(new PreparedChatGatewayRequest("BYCLAW_DSH_u1",
            "9000", "hello", "u1", "User One", "ASK_AGENT", "-1", "9002", "9001_9002", Map.of("cwd", "/by/projects/-1"),
            Map.of("requestId", "req-1")));
        when(bindings.ensure(OWNER)).thenReturn(new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET));
        when(processor.processEvent(any(), anyString(), anyString())).thenAnswer(call -> {
            JSONObject event = call.getArgument(0);
            String type = event.getString("event_type");
            return "appStreamResponse".equals(type) || "error".equals(type)
                ? StreamDispatchResult.terminalHandled(ctx) : StreamDispatchResult.HANDLED;
        });
        DirectChatGateway.TurnTransport transport = (runtime, turn) -> {
            opened.add(runtime.sandboxId() + ":" + turn.requestId());
            if (transportFailure != null) {
                return new BufferedReader(new Reader() {
                    private boolean first = true;

                    @Override
                    public int read(char[] buffer, int offset, int length) throws IOException {
                        if (first) {
                            first = false;
                            String head = line("admission", "accepted", null) + "\n" + event("answerDelta") + "\n";
                            head.getChars(0, head.length(), buffer, offset);
                            return head.length();
                        }
                        throw transportFailure;
                    }

                    @Override
                    public void close() {
                    }
                });
            }
            return new BufferedReader(new StringReader(runtimeOutput));
        };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);
        gateway = new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager, registry,
            new DirectRuntimeProperties(), Runnable::run, clock);
    }

    private String event(String type) {
        JSONObject data = new JSONObject();
        data.put("trace_id", "9001_9002");
        data.put("session_id", "9000");
        data.put("event_type", type);
        return line("event", null, data);
    }

    private static String line(String type, String state, JSONObject data) {
        JSONObject line = new JSONObject();
        line.put("type", type);
        if (state != null) line.put("state", state);
        if (data != null) line.put("data", data);
        return line.toJSONString();
    }

    @Test
    void directTurnUsesThePreparedContextAndTheSharedProjectionWithoutLegacyTransport() throws Exception {
        runtimeOutput = String.join("\n", line("admission", "accepted", null), event("answerDelta"),
            event("answerDelta"), event("appStreamResponse"), line("end", "completed", null));

        gateway.execute(ctx, OWNER);

        assertThat(ctx.directTransport).isTrue();
        assertThat(ctx.asyncResponse).isTrue();
        verify(outputStreamManager).putContext("9000", ctx);
        verify(registry).markRunning(ctx);
        verify(routeService, never()).route(any());
        assertThat(opened).containsExactly("sbx-1:req-1");
        ArgumentCaptor<String> streamIds = ArgumentCaptor.forClass(String.class);
        verify(processor, times(3)).processEvent(any(), streamIds.capture(), eq("direct"));
        assertThat(streamIds.getAllValues()).containsExactly("1790000000000-0", "1790000000000-1", "1790000000000-2");
        verify(processor, times(1)).afterAcknowledge(any());
    }

    @Test
    void eventsOfAnotherTurnAreNeverApplied() throws Exception {
        JSONObject foreign = new JSONObject();
        foreign.put("trace_id", "other-trace");
        foreign.put("session_id", "9000");
        foreign.put("event_type", "answerDelta");
        runtimeOutput = String.join("\n", line("event", null, foreign), event("appStreamResponse"));
        gateway.execute(ctx, OWNER);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor, times(1)).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getString("event_type")).isEqualTo("appStreamResponse");
    }

    @Test
    void aTurnThatEndsWithoutATerminalEventIsClosedWithAVisibleError() throws Exception {
        runtimeOutput = String.join("\n", line("admission", "duplicate", null), line("end", "unknown", null));
        gateway.execute(ctx, OWNER);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        JSONObject error = events.getValue();
        assertThat(error.getString("event_type")).isEqualTo("error");
        assertThat(error.getJSONObject("metadata").getString("error")).contains("结果未知");
        assertThat(error.getString("message_id")).isEqualTo("9002");
        verify(processor).afterAcknowledge(any());
    }

    @Test
    void anUnavailableRuntimeEndsTheTurnWithoutContactingIt() throws Exception {
        when(bindings.ensure(OWNER)).thenThrow(new RuntimeUnavailableException("provisioning", true,
            "运行资源暂时不足，已保留原会话，请稍后重试", null));
        gateway.execute(ctx, OWNER);
        assertThat(opened).isEmpty();
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("运行资源暂时不足");
    }

    @Test
    void aBrokenStreamIsReportedAsUnknownAndNotResent() throws Exception {
        transportFailure = new IOException("connection reset");
        gateway.execute(ctx, OWNER);
        assertThat(opened).hasSize(1);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor, times(2)).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getAllValues().get(1).getJSONObject("metadata").getString("error")).contains("结果未知");
    }

    @Test
    void anIneligibleTurnEndsVisiblyWithoutRuntimeWorkOrLegacyFallback() throws Exception {
        when(routeService.prepareDirectGatewayRequest(ctx))
            .thenThrow(new IllegalArgumentException("HARNESS runtime routing does not match the current owner scope"));
        ctx.setModelAnswerMessageId(9002L);
        gateway.execute(ctx, OWNER);
        verifyNoInteractions(bindings);
        verify(routeService, never()).route(any());
        assertThat(opened).isEmpty();
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getString("event_type")).isEqualTo("error");
        assertThat(events.getValue().getString("message_id")).isEqualTo("9002");
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("准入");
    }

    @Test
    void theSignedBodyCarriesThePreparedTurnUnchanged() throws Exception {
        List<DirectProtocol.SignedTurn> turns = new ArrayList<>();
        DirectChatGateway capturing = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            turns.add(turn);
            return new BufferedReader(new StringReader(event("appStreamResponse")));
        }, processor, outputStreamManager, registry, new DirectRuntimeProperties(), Runnable::run, Clock.systemUTC());
        capturing.execute(ctx, OWNER);
        JSONObject body = JSON.parseObject(new String(turns.get(0).body(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.getJSONObject("command").getJSONObject("body").getString("content")).isEqualTo("hello");
        assertThat(turns.get(0).signature()).isEqualTo(DirectProtocol.sign(SECRET, turns.get(0).admission()));
    }
}
