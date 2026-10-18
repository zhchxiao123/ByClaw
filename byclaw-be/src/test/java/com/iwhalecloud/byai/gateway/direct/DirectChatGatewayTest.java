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

    private String laneOutput;
    private IOException transportFailure;
    private DirectChatGateway gateway;
    private ChatProcessContext ctx;
    private InMemoryRequestAdmissions admissionRows;
    private DirectAdmissions admissions;
    private final List<String> halted = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        routeService = mock(RouteService.class);
        bindings = mock(RuntimeBindingService.class);
        processor = mock(StreamRecordProcessor.class);
        outputStreamManager = mock(OutputStreamManager.class);
        registry = mock(RunningOutputStreamRegistry.class);
        when(outputStreamManager.withSessionLock(anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        AssistantChatDto dto = new AssistantChatDto();
        dto.setChatContent("hello");
        dto.setExtParams(new java.util.HashMap<>(Map.of("requestId", "req-1")));
        ctx = new ChatProcessContext(new ByteArrayOutputStream(), dto);
        ctx.setSessionId(9000L);
        ctx.setTraceId("9001_9002");
        ctx.setUserMessageId(9001L);
        ctx.setModelAnswerMessageId(9002L);
        ctx.transport = com.iwhalecloud.byai.state.domain.chat.enums.ChatTransport.WEBSOCKET;
        ctx.setParams(new java.util.HashMap<>(Map.of("worker_agent_type", "BY_SUPER")));
        admissionRows = new InMemoryRequestAdmissions();
        admissions = new DirectAdmissions(admissionRows);
        when(routeService.prepareDirectGatewayRequest(ctx)).thenReturn(new PreparedChatGatewayRequest("BYCLAW_DSH_u1",
            "9000", "hello", "u1", "User One", "ASK_AGENT", "-1", "9002", "9001_9002", Map.of("cwd", "/by/projects/-1"),
            Map.of("requestId", "req-1")));
        // An ordinary turn is one runtime request; tests stub that single request.
        when(routeService.prepareDirectGatewayRequests(any())).thenAnswer(call ->
            java.util.List.of(routeService.prepareDirectGatewayRequest(call.getArgument(0))));
        when(bindings.ensure(OWNER)).thenReturn(new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET));
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.empty());
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
            return new BufferedReader(new StringReader(laneOutput != null && turn.requestId().contains("#lane")
                ? laneOutput : runtimeOutput));
        };
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);
        gateway = new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager, registry,
            admissions, new DirectRuntimeProperties(), Runnable::run, clock, () -> halted.add("halt"));
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
        verify(routeService, times(2)).sendRuntimeProgress(eq(ctx), anyString(), any());
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
        assertThat(admissions.find(9000L, "req-1").getAnswerMessageId()).isEqualTo(9002L);
    }

    @Test
    void anyEngineRunsAsTheOwnersDshAndAnSseRequestStreamsAndStoresOnItsOwnThread() throws Exception {
        ctx.transport = com.iwhalecloud.byai.state.domain.chat.enums.ChatTransport.HTTP_SSE;
        runtimeOutput = String.join("\n", line("admission", "accepted", null), event("answerDelta"),
            event("appStreamResponse"), line("end", "completed", null));

        gateway.execute(ctx, OWNER);

        assertThat(ctx.getParams().get("worker_agent_type")).as("super assistant turn runs on DSH").isEqualTo("HARNESS");
        assertThat(ctx.gatewayEventQueue).isNotNull();
        verify(routeService).streamQueuedEvents(ctx, "9000", false);
        assertThat(ctx.asyncResponse).as("the request thread stores the message").isFalse();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void aMultiAgentTurnRunsEachLaneAsItsOwnRuntimeRequest() throws Exception {
        PreparedChatGatewayRequest first = new PreparedChatGatewayRequest("BYCLAW_DSH_u1", "9000", "hello", "u1", "User One",
            "ASK_AGENT", "-1", "9002", "9001_9002", Map.of("byclaw_root_session_id", "9000-lane-1"), Map.of("requestId", "req-1"));
        PreparedChatGatewayRequest second = new PreparedChatGatewayRequest("BYCLAW_DSH_u1", "9000", "hello", "u1", "User One",
            "ASK_AGENT", "-1", "9003", "lane-2", Map.of("byclaw_root_session_id", "9000-lane-2"),
            Map.of("requestId", "req-1#lane1"));
        org.mockito.Mockito.doReturn(java.util.List.of(first, second)).when(routeService).prepareDirectGatewayRequests(ctx);
        // The router reports the turn terminal only once every lane ended.
        org.mockito.Mockito.doReturn(StreamDispatchResult.HANDLED).when(processor).processEvent(any(), anyString(), anyString());
        ctx.getMultiAgentTraceIds().addAll(java.util.List.of("9001_9002", "lane-2"));
        runtimeOutput = String.join("\n", line("admission", "accepted", null), event("answerDelta"),
            event("appStreamResponse"), line("end", "completed", null));
        JSONObject laneEvent = new JSONObject();
        laneEvent.put("trace_id", "lane-2");
        laneEvent.put("session_id", "9000");
        laneEvent.put("event_type", "appStreamResponse");
        laneOutput = String.join("\n", line("admission", "accepted", null), line("event", null, laneEvent),
            line("end", "completed", null));

        gateway.execute(ctx, OWNER);

        assertThat(opened).containsExactly("sbx-1:req-1", "sbx-1:req-1#lane1");
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
        assertThat(admissionRows.state(9000L, "req-1#lane1")).isEqualTo(DirectAdmissions.COMPLETED);
        assertThat(admissions.find(9000L, "req-1#lane1").getAnswerMessageId()).isEqualTo(9003L);
        verify(processor, times(3)).processEvent(any(), anyString(), eq("direct"));
        verify(routeService, times(2)).sendRuntimeProgress(eq(ctx), anyString(), any());
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor, times(3)).processEvent(events.capture(), anyString(), eq("direct"));
        assertThat(events.getAllValues()).as("a lane that ends before the others is not reported as failed")
            .noneMatch(event -> "error".equals(event.getString("event_type")));
    }

    @Test
    void anUnexpectedFailureBeforeAdmissionEndsTheTurnVisiblyInsteadOfThrowing() {
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
            .when(routeService).prepareDirectGatewayRequests(ctx);

        gateway.execute(ctx, OWNER);

        assertThat(ctx.asyncResponse).isTrue();
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), eq("direct"));
        assertThat(events.getValue().getString("event_type")).isEqualTo("error");
        assertThat(opened).isEmpty();
    }

    private DirectChatGateway browserStreaming(DirectChatGateway.TurnTransport transport) {
        DirectRuntimeProperties properties = new DirectRuntimeProperties();
        properties.setBrowserStream(true);
        return new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager, registry, admissions,
            properties, Runnable::run, Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC), () -> { });
    }

    private JSONObject handOffOf(ByteArrayOutputStream page) {
        return JSONObject.parseObject(page.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void aPageThatAsksForItGetsAReadOnlyTicketOnceTheRuntimeAdmittedTheTurn() throws Exception {
        ctx.getAssistantChatDto().getExtParams().put(DirectChatGateway.BROWSER_STREAM_PARAM, true);
        ByteArrayOutputStream page = (ByteArrayOutputStream) ctx.res;
        runtimeOutput = String.join("\n", line("admission", "accepted", null), event("answerDelta"),
            event("appStreamResponse"), line("end", "completed", null));

        browserStreaming((runtime, turn) -> {
            opened.add(new String(turn.body(), java.nio.charset.StandardCharsets.UTF_8));
            return new BufferedReader(new StringReader(runtimeOutput));
        }).execute(ctx, OWNER);

        assertThat(opened).as("the platform sends the command; the page never does").hasSize(1);
        JSONObject handOff = handOffOf(page);
        assertThat(handOff.getString("event")).isEqualTo("directTurn");
        assertThat(handOff.getString("messageId")).isEqualTo("9002");
        assertThat(handOff.keySet()).doesNotContain("body", "admission");
        JSONObject claims = JSONObject.parseObject(new String(java.util.Base64.getUrlDecoder().decode(handOff.getString("watch")),
            java.nio.charset.StandardCharsets.UTF_8));
        assertThat(claims.getString("purpose")).isEqualTo("watch");
        assertThat(claims.getString("traceId")).isEqualTo("9001_9002");
        assertThat(claims.keySet()).doesNotContain("bodySha256", "intentDigest");
        assertThat(page.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("traffic").doesNotContain(SECRET);
        assertThat(ctx.res).as("the runtime output is the page's own; projection only persists").isNull();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.ACCEPTED);
        verify(processor, never()).processEvent(any(), anyString(), eq("direct"));
    }

    @Test
    void withoutTheOptInThePlatformStillStreamsTheTurn() throws Exception {
        runtimeOutput = String.join("\n", line("admission", "accepted", null), event("appStreamResponse"),
            line("end", "completed", null));
        browserStreaming((runtime, turn) -> new BufferedReader(new StringReader(runtimeOutput))).execute(ctx, OWNER);
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void onlyAnUnexpiredWatchTicketForTheCallersSessionIsAuthorized() throws Exception {
        ctx.getAssistantChatDto().getExtParams().put(DirectChatGateway.BROWSER_STREAM_PARAM, true);
        ByteArrayOutputStream page = (ByteArrayOutputStream) ctx.res;
        runtimeOutput = String.join("\n", line("admission", "accepted", null), line("end", "completed", null));
        List<String> admissionHeaders = new ArrayList<>();
        DirectChatGateway streaming = browserStreaming((runtime, turn) -> {
            admissionHeaders.add(turn.admission());
            admissionHeaders.add(turn.signature());
            return new BufferedReader(new StringReader(runtimeOutput));
        });
        streaming.execute(ctx, OWNER);
        JSONObject handOff = handOffOf(page);
        String watch = handOff.getString("watch");
        String signature = handOff.getString("signature");
        ReadyRuntime runtime = new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET);
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(runtime));

        assertThat(streaming.authorizeBrowserTurn(watch, signature, sessionId -> OWNER)).contains(runtime);
        assertThat(streaming.authorizeBrowserTurn(admissionHeaders.get(0), admissionHeaders.get(1), sessionId -> OWNER))
            .as("an admission is not a watch ticket").isEmpty();
        assertThat(streaming.authorizeBrowserTurn(watch, "0".repeat(64), sessionId -> OWNER)).isEmpty();
        assertThat(streaming.authorizeBrowserTurn(watch, signature, sessionId -> null)).as("not the caller's").isEmpty();
        assertThat(streaming.authorizeBrowserTurn(watch, signature,
            sessionId -> new RuntimeOwnerScope(200L, "u2", 0L))).as("another owner").isEmpty();
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 4L, "u100.e0", "sbx-1", "traffic", SECRET)));
        assertThat(streaming.authorizeBrowserTurn(watch, signature, sessionId -> OWNER)).as("another generation").isEmpty();
        assertThat(streaming.authorizeBrowserTurn("not-base64!", signature, sessionId -> OWNER)).isEmpty();
    }

    @Test
    void theReconcilerLeavesAPageWatchedSessionAloneUntilThePageSettles() throws Exception {
        ctx.getAssistantChatDto().getExtParams().put(DirectChatGateway.BROWSER_STREAM_PARAM, true);
        runtimeOutput = String.join("\n", line("admission", "accepted", null), line("end", "completed", null));
        List<String> reads = new ArrayList<>();
        DirectChatGateway.TurnTransport transport = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                return new BufferedReader(new StringReader(runtimeOutput));
            }

            @Override
            public DirectChatGateway.OutboxPage readOutboxPage(ReadyRuntime runtime, long sessionId, long after) {
                reads.add(sessionId + ">" + after);
                return new DirectChatGateway.OutboxPage("L1", 0, List.of());
            }

            @Override
            public Map<Long, Long> pendingProjection(ReadyRuntime runtime) {
                return Map.of(9000L, 3L);
            }
        };
        RecordingProgress progress = new RecordingProgress();
        progress.ready.add(OWNER.key());
        DirectChatGateway streaming = browserStreaming(transport).withProjectionProgress(progress);
        streaming.execute(ctx, OWNER);
        when(bindings.readyWithoutWake(OWNER.key())).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));

        com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission open = admissions.find(9000L, "req-1");
        open.setUpdateTime(new java.util.Date(System.currentTimeMillis() - 60_000L));
        progress.open.add(open);
        streaming.reconcileOnce();
        assertThat(reads).as("the page has not read it yet").isEmpty();

        streaming.settleBrowserTurn(OWNER, 9000L, null);
        assertThat(reads).containsExactly("9000>0");
    }

    @Test
    void aReloadedPageGetsAFreshTicketForTheTurnUntilItSettles() throws Exception {
        ctx.getAssistantChatDto().getExtParams().put(DirectChatGateway.BROWSER_STREAM_PARAM, true);
        runtimeOutput = String.join("\n", line("admission", "accepted", null), line("end", "completed", null));
        DirectChatGateway streaming = browserStreaming((runtime, turn) -> new BufferedReader(new StringReader(runtimeOutput)));
        assertThat(streaming.rewatchBrowserTurn(OWNER, 9000L, null)).as("no page holds it yet").isEmpty();
        ByteArrayOutputStream page = (ByteArrayOutputStream) ctx.res;
        streaming.execute(ctx, OWNER);
        JSONObject first = handOffOf(page);
        ReadyRuntime runtime = new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET);
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(runtime));

        JSONObject again = streaming.rewatchBrowserTurn(OWNER, 9000L, null).orElseThrow();
        for (String key : List.of("requestId", "sessionId", "traceId", "messageId", "queryMessageId")) {
            assertThat(again.getString(key)).as(key).isEqualTo(first.getString(key));
        }
        assertThat(streaming.authorizeBrowserTurn(again.getString("watch"), again.getString("signature"),
            sessionId -> OWNER)).contains(runtime);

        streaming.settleBrowserTurn(OWNER, 9000L, null);
        assertThat(streaming.rewatchBrowserTurn(OWNER, 9000L, null)).as("settled: the platform shows it").isEmpty();
    }

    @Test
    void aSecondPageStreamedTurnKeepsTheSessionHeldUntilBothSettle() throws Exception {
        ctx.getAssistantChatDto().getExtParams().put(DirectChatGateway.BROWSER_STREAM_PARAM, true);
        runtimeOutput = String.join("\n", line("admission", "accepted", null), line("end", "completed", null));
        List<String> reads = new ArrayList<>();
        DirectChatGateway.TurnTransport transport = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                return new BufferedReader(new StringReader(runtimeOutput));
            }

            @Override
            public DirectChatGateway.OutboxPage readOutboxPage(ReadyRuntime runtime, long sessionId, long after) {
                reads.add(sessionId + ">" + after);
                return new DirectChatGateway.OutboxPage("L1", 0, List.of());
            }
        };
        DirectChatGateway streaming = browserStreaming(transport);
        streaming.execute(ctx, OWNER);
        AssistantChatDto dto = new AssistantChatDto();
        dto.setChatContent("and then");
        dto.setExtParams(new java.util.HashMap<>(Map.of("requestId", "req-2", DirectChatGateway.BROWSER_STREAM_PARAM, true)));
        ChatProcessContext second = new ChatProcessContext(new ByteArrayOutputStream(), dto);
        second.setSessionId(9000L);
        second.setTraceId("9003_9004");
        second.setUserMessageId(9003L);
        second.setModelAnswerMessageId(9004L);
        second.transport = ctx.transport;
        second.setParams(new java.util.HashMap<>(Map.of("worker_agent_type", "BY_SUPER")));
        when(routeService.prepareDirectGatewayRequest(second)).thenReturn(new PreparedChatGatewayRequest("BYCLAW_DSH_u1",
            "9000", "and then", "u1", "User One", "ASK_AGENT", "-1", "9004", "9003_9004", Map.of("cwd", "/by/projects/-1"),
            Map.of("requestId", "req-2")));
        streaming.execute(second, OWNER);
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));

        assertThat(streaming.rewatchBrowserTurn(OWNER, 9000L, "9001_9002").orElseThrow().getString("messageId"))
            .isEqualTo("9002");
        assertThat(streaming.rewatchBrowserTurn(OWNER, 9000L, "9003_9004").orElseThrow().getString("messageId"))
            .isEqualTo("9004");
        streaming.settleBrowserTurn(OWNER, 9000L, "9001_9002");
        assertThat(reads).as("a page still reads the second turn").isEmpty();
        assertThat(streaming.rewatchBrowserTurn(OWNER, 9000L, "9001_9002")).isEmpty();
        streaming.settleBrowserTurn(OWNER, 9000L, "9003_9004");
        assertThat(reads).containsExactly("9000>0");
    }

    @Test
    void aStorageRefusalIsRecordedAsNotAdmittedWithAClearMessage() throws Exception {
        DirectChatGateway refusing = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            throw new DirectChatGateway.RuntimeRefusedException(503, "storage_unavailable");
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
        refusing.execute(ctx, OWNER);
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.REJECTED);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("未被接纳");
    }

    @Test
    void aRepeatedFirstTurnNeverStartsASecondRun() throws Exception {
        admissions.reserve(OWNER, 9000L, "req-1", DirectIntent.digest(ctx.getAssistantChatDto()));
        admissions.record(9000L, "req-1", DirectAdmissions.COMPLETED, "earlier");
        gateway.execute(ctx, OWNER);
        assertThat(opened).isEmpty();
        verifyNoInteractions(bindings);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("已执行完成");
    }

    @Test
    void theAcceptanceFaultHaltsOnlyWhenExplicitlyEnabled() throws Exception {
        DirectRuntimeProperties properties = new DirectRuntimeProperties();
        properties.setFault(DirectChatGateway.FAULT_HALT_BEFORE_DISPATCH);
        runtimeOutput = String.join("\n", event("appStreamResponse"), line("end", "completed", null));
        new DirectChatGateway(routeService, bindings, (runtime, turn) -> new BufferedReader(new StringReader(runtimeOutput)),
            processor, outputStreamManager, registry, admissions, properties, Runnable::run, Clock.systemUTC(),
            () -> halted.add("halt")).execute(ctx, OWNER);
        assertThat(halted).as("fault without the acceptance opt-in is ignored").isEmpty();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void aTurnRefusedBecauseTheRuntimeWasDrainingResumesAndIsSentOnce() throws Exception {
        List<String> attempts = new ArrayList<>();
        DirectChatGateway retrying = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            attempts.add(turn.requestId());
            if (attempts.size() == 1) throw new DirectChatGateway.RuntimeRefusedException(503, "draining");
            return new BufferedReader(new StringReader(String.join("\n", line("admission", "accepted", null),
                event("appStreamResponse"), line("end", "completed", null))));
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
        retrying.execute(ctx, OWNER);
        assertThat(attempts).containsExactly("req-1", "req-1");
        verify(bindings, times(2)).ensure(OWNER);
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void aWarmRuntimeShowsNoPreparationProgress() throws Exception {
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        runtimeOutput = String.join("\n", event("appStreamResponse"), line("end", "completed", null));
        gateway.execute(ctx, OWNER);
        verify(routeService, never()).sendRuntimeProgress(any(), anyString(), any());
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
        when(bindings.ensure(OWNER)).thenThrow(new RuntimeUnavailableException("provisioning", RuntimeFault.CAPACITY, null));
        gateway.execute(ctx, OWNER);
        assertThat(opened).isEmpty();
        assertThat(admissionRows.rows.values().iterator().next().getDetail()).as("one fault identity")
            .isEqualTo("runtime capacity");
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("运行资源暂时不足");
    }

    @Test
    void withAdmissionClosedForARollbackATurnEndsVisiblyWithoutWakingOrSending() throws Exception {
        gateway.withAdmission(() -> false).execute(ctx, OWNER);

        assertThat(opened).isEmpty();
        verify(bindings, never()).ensure(any(RuntimeOwnerScope.class));
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).isEqualTo(DirectChatGateway.ADMISSION_CLOSED);
    }

    @Test
    void aStreamLostAfterAcceptanceStaysOpenForReconciliationAndIsNotResent() throws Exception {
        transportFailure = new IOException("connection reset");
        gateway.execute(ctx, OWNER);
        assertThat(opened).hasSize(1);
        verify(processor, times(1)).processEvent(any(), anyString(), anyString());
        verify(processor, never()).afterAcknowledge(any());
        assertThat(admissionRows.state(9000L, "req-1")).as("accepted then lost is unknown").isEqualTo(DirectAdmissions.UNKNOWN);
    }

    private class RecordingProgress implements DirectChatGateway.ProjectionProgress {
        long through;
        String lineage;
        final List<com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission> open = new ArrayList<>();
        final List<String> ready = new ArrayList<>();

        @Override
        public List<String> readyOwners() {
            return ready;
        }

        @Override
        public long throughSeq(long sessionId) {
            return through;
        }

        @Override
        public String lineage(long sessionId) {
            return lineage;
        }

        @Override
        public void rebase(long sessionId, String ownerScope, String lineage, long throughSeq) {
            this.lineage = lineage;
            through = throughSeq;
        }

        @Override
        public void advance(long sessionId, String ownerScope, long throughSeq) {
            through = Math.max(through, throughSeq);
        }

        @Override
        public List<com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission> openAdmissions(int settle, int limit) {
            return open;
        }
    }

    private JSONObject item(long seq, String type) {
        JSONObject event = new JSONObject();
        event.put("trace_id", "9001_9002");
        event.put("session_id", "9000");
        event.put("event_type", type);
        JSONObject item = new JSONObject();
        item.put("seq", seq);
        item.put("event", event);
        return item;
    }

    private DirectChatGateway reconcilerWith(DirectChatGateway.TurnTransport transport, RecordingProgress progress) {
        return new DirectChatGateway(routeService, bindings, transport, processor, outputStreamManager,
            registry, admissions, new DirectRuntimeProperties(), Runnable::run, Clock.systemUTC(), () -> { })
            .withProjectionProgress(progress);
    }

    private DirectChatGateway.TurnTransport pages(List<String> reads, java.util.function.LongFunction<DirectChatGateway.OutboxPage> page) {
        return new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("reconciliation never re-sends a turn");
            }

            @Override
            public DirectChatGateway.OutboxPage readOutboxPage(ReadyRuntime runtime, long sessionId, long after) {
                reads.add(sessionId + ">" + after);
                return page.apply(after);
            }
        };
    }

    private void openAdmission(RecordingProgress progress, String state, long ageSeconds) {
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 9001L, 9002L, "9001_9002");
        admissions.record(9000L, "req-1", state, "test");
        var open = admissions.find(9000L, "req-1");
        open.setUpdateTime(new java.util.Date(System.currentTimeMillis() - ageSeconds * 1000L));
        progress.open.add(open);
    }

    @Test
    void aStreamLostBeforeAcceptanceStaysOpenUntilTheRuntimeSaysItNeverAdmittedTheTurn() throws Exception {
        DirectChatGateway.TurnTransport lost = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) throws IOException {
                throw new IOException("connection reset before the admission line");
            }

            @Override
            public java.util.Optional<String> turnState(ReadyRuntime runtime, long sessionId, String requestId) {
                return java.util.Optional.of("not_admitted");
            }
        };
        RecordingProgress progress = new RecordingProgress();
        DirectChatGateway direct = reconcilerWith(lost, progress);
        direct.execute(ctx, OWNER);
        assertThat(admissionRows.state(9000L, "req-1")).as("its acceptance may only have been lost").isEqualTo(DirectAdmissions.SENDING);
        verify(processor, never()).processEvent(any(), anyString(), anyString());

        var open = admissions.find(9000L, "req-1");
        open.setUpdateTime(new java.util.Date(System.currentTimeMillis() - 60_000L));
        progress.open.add(open);
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        direct.reconcileOnce();

        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.REJECTED);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), eq("direct-reconcile"));
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("未接收本轮");
    }

    @Test
    void anOutcomeTheRuntimeRecordsAsUnknownClosesOnceNothingIsLeftToProject() throws Exception {
        DirectChatGateway.TurnTransport unknown = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("never re-sent");
            }

            @Override
            public java.util.Optional<String> turnState(ReadyRuntime runtime, long sessionId, String requestId) {
                return java.util.Optional.of("unknown");
            }
        };
        RecordingProgress progress = new RecordingProgress();
        openAdmission(progress, DirectAdmissions.UNKNOWN, 60);
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        List<Long> reset = new ArrayList<>();

        reconcilerWith(unknown, progress).withSessionRuntimeReset(reset::add).reconcileOnce();

        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.FAILED);
        assertThat(admissionRows.detail(9000L, "req-1")).startsWith("unknown");
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), eq("direct-reconcile"));
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("结果未知");
        assertThat(reset).containsExactly(9000L);
    }

    @Test
    void aTurnLeftUnsentByAPlatformRestartEndsVisiblyAsNotSentWithoutWakingTheRuntime() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        openAdmission(progress, DirectAdmissions.DISPATCHING, 3600);
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.empty());

        reconcilerWith(pages(new ArrayList<>(), after -> null), progress).reconcileOnce();

        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.REJECTED);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), eq("direct-reconcile"));
        assertThat(events.getValue().getString("event_type")).isEqualTo("error");
        assertThat(events.getValue().getString("message_id")).isEqualTo("9002");
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("未发送");
        verify(bindings, never()).ensure(any(RuntimeOwnerScope.class));
    }

    @Test
    void aRecentlyDispatchedTurnIsLeftToTheBackendThatMayStillBeWakingItsRuntime() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        openAdmission(progress, DirectAdmissions.DISPATCHING, 20);
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.empty());

        reconcilerWith(pages(new ArrayList<>(), after -> null), progress).reconcileOnce();

        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.DISPATCHING);
        verify(processor, never()).processEvent(any(), anyString(), anyString());
    }

    @Test
    void aRunEndedByARuntimeRestartIsClosedAsUnknownFromItsOutboxTerminal() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        openAdmission(progress, DirectAdmissions.UNKNOWN, 60);
        JSONObject interrupted = item(7, "error");
        JSONObject metadata = new JSONObject();
        metadata.put("error", "运行环境已重启，本轮在执行中被中断，结果未知");
        metadata.put("outcome", "unknown");
        interrupted.getJSONObject("event").put("metadata", metadata);
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));

        List<Long> reset = new ArrayList<>();
        reconcilerWith(pages(new ArrayList<>(), after -> new DirectChatGateway.OutboxPage("L1", 7, List.of(interrupted))), progress)
            .withSessionRuntimeReset(reset::add).reconcileOnce();

        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.FAILED);
        assertThat(admissionRows.detail(9000L, "req-1")).contains("unknown").contains("not re-run");
        assertThat(reset).as("the session no longer shows the ended run as active").containsExactly(9000L);
    }

    @Test
    void aChangedOutboxLineageResyncsFromItsStartInsteadOfSkippingLowerSequences() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        progress.through = 40;
        progress.lineage = "L1";
        openAdmission(progress, DirectAdmissions.UNKNOWN, 60);
        List<String> reads = new ArrayList<>();
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));

        reconcilerWith(pages(reads, after -> new DirectChatGateway.OutboxPage("L2", 2,
            after < 2 ? List.of(item(1, "answerDelta"), item(2, "answerDelta")) : List.of())), progress).reconcileOnce();

        assertThat(reads).containsExactly("9000>40", "9000>0");
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(processor, times(2)).processEvent(any(), ids.capture(), eq("direct-reconcile"));
        assertThat(ids.getAllValues()).containsExactly("1-0", "2-0");
        assertThat(progress.lineage).isEqualTo("L2");
        assertThat(progress.through).isEqualTo(2);
    }

    @Test
    void aRolledBackRuntimeLogMovesThePositionBackToWhereTheLogNowEnds() throws Exception {
        RecordingProgress progress = new RecordingProgress();
        progress.through = 40;
        progress.lineage = "L1";
        openAdmission(progress, DirectAdmissions.UNKNOWN, 60);
        List<String> reads = new ArrayList<>();
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));

        reconcilerWith(pages(reads, after -> new DirectChatGateway.OutboxPage("L1", 5, List.of())), progress).reconcileOnce();

        assertThat(progress.through).isEqualTo(5);
        assertThat(progress.lineage).isEqualTo("L1");
        assertThat(reads).containsExactly("9000>40", "9000>5");
    }

    @Test
    void theReconcilerProjectsMissedOutboxEventsOnceAndAcknowledges() throws Exception {
        List<String> reads = new ArrayList<>();
        List<Long> acks = new ArrayList<>();
        DirectChatGateway.TurnTransport outbox = new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("reconciliation never re-sends a turn");
            }

            @Override
            public List<JSONObject> readOutbox(ReadyRuntime runtime, long sessionId, long after) {
                reads.add(sessionId + ">" + after);
                return List.of(item(3, "answerDelta"), item(4, "appStreamResponse"));
            }

            @Override
            public void ackOutbox(ReadyRuntime runtime, long sessionId, long throughSeq) {
                acks.add(throughSeq);
            }
        };
        RecordingProgress progress = new RecordingProgress();
        progress.through = 2;
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 9001L, 9002L, "9001_9002");
        admissions.record(9000L, "req-1", DirectAdmissions.UNKNOWN, "stream lost");
        progress.open.add(admissions.find(9000L, "req-1"));
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        DirectChatGateway reconciler = new DirectChatGateway(routeService, bindings, outbox, processor, outputStreamManager,
            registry, admissions, new DirectRuntimeProperties(), Runnable::run, Clock.systemUTC(), () -> { })
            .withProjectionProgress(progress);

        assertThat(reconciler.reconcileOnce()).isEqualTo(2);

        assertThat(reads).containsExactly("9000>2");
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(processor, times(2)).processEvent(any(), ids.capture(), eq("direct-reconcile"));
        assertThat(ids.getAllValues()).as("stable stream ids from runtime sequences").containsExactly("3-0", "4-0");
        assertThat(progress.through).isEqualTo(4);
        assertThat(acks).containsExactly(4L);
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    private DirectChatGateway controlling(List<String> calls) {
        return new DirectChatGateway(routeService, bindings, new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("no turn");
            }

            @Override
            public JSONObject control(ReadyRuntime runtime, long sessionId, String action, String requestId) {
                calls.add(action + ":" + requestId);
                JSONObject result = new JSONObject();
                result.put("stopped", "stop".equals(action) ? List.of("req-1") : List.of());
                result.put("cleared", "clear-queue".equals(action) ? List.of("req-2") : List.of());
                return result;
            }
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
    }

    @Test
    void stopTargetsTheTurnOfTheAnswerMessageAndClearQueueIsSeparate() {
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 9001L, 9002L, "9001_9002");
        admissions.record(9000L, "req-1", DirectAdmissions.ACCEPTED, "runtime accepted");
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        List<String> calls = new ArrayList<>();
        DirectChatGateway control = controlling(calls);

        control.stop(OWNER, 9000L, 9002L);
        control.stop(OWNER, 9000L, null);
        control.clearQueue(OWNER, 9000L);

        assertThat(calls).containsExactly("stop:req-1", "stop:null", "clear-queue:null");
    }

    @Test
    void jobRequestsGoToTheOwnersRunningRuntimeOnlyAndNeverWakeIt() {
        List<String> paths = new ArrayList<>();
        DirectChatGateway jobs = new DirectChatGateway(routeService, bindings, new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("no turn");
            }

            @Override
            public JSONObject sessionResource(ReadyRuntime runtime, String method, String path) {
                paths.add(method + " " + path);
                return new JSONObject();
            }
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.empty());
        assertThat(jobs.jobs(OWNER, 9000L, null, false).getString("result")).isEqualTo("not_running");
        verify(bindings, never()).ensure(any());

        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        jobs.jobs(OWNER, 9000L, null, false);
        jobs.jobs(OWNER, 9000L, "bash-1", false);
        jobs.jobs(OWNER, 9000L, "bash-1", true);
        assertThat(paths).containsExactly("GET /plugins/byclaw-direct/v1/sessions/9000/jobs",
            "GET /plugins/byclaw-direct/v1/sessions/9000/jobs/bash-1", "POST /plugins/byclaw-direct/v1/sessions/9000/jobs/bash-1/kill");
        assertThatThrownBy(() -> jobs.jobs(OWNER, 9000L, "../../status", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stoppingAFinishedTurnOrAPausedRuntimeChangesNothing() {
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 9001L, 9002L, "9001_9002");
        admissions.record(9000L, "req-1", DirectAdmissions.COMPLETED, "done");
        List<String> calls = new ArrayList<>();
        DirectChatGateway control = controlling(calls);

        assertThat(control.stop(OWNER, 9000L, 9002L).getString("result")).isEqualTo("completed");
        assertThat(control.stop(OWNER, 9000L, 777L).getString("result")).as("forged message id").isEqualTo("not_found");
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.empty());
        assertThat(control.stop(OWNER, 9000L, null).getString("result")).isEqualTo("not_running");
        assertThat(calls).isEmpty();
        verify(bindings, never()).ensure(any());
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void runtimesReportingPendingProjectionAreSweptAndAlreadyProjectedPositionsReacknowledged() throws Exception {
        List<String> reads = new ArrayList<>();
        List<Long> acks = new ArrayList<>();
        RecordingProgress progress = new RecordingProgress() {
            @Override
            public List<String> readyOwners() {
                return List.of("u100.e0");
            }
        };
        progress.through = 7;
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        DirectChatGateway sweeper = new DirectChatGateway(routeService, bindings, new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("no turn");
            }

            @Override
            public Map<Long, Long> pendingProjection(ReadyRuntime runtime) {
                return Map.of(9000L, 7L);
            }

            @Override
            public List<JSONObject> readOutbox(ReadyRuntime runtime, long sessionId, long after) {
                reads.add(sessionId + ">" + after);
                return List.of();
            }

            @Override
            public void ackOutbox(ReadyRuntime runtime, long sessionId, long throughSeq) {
                acks.add(throughSeq);
            }
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { }).withProjectionProgress(progress);

        sweeper.reconcileOnce();

        assertThat(reads).containsExactly("9000>7");
        assertThat(acks).as("a lost acknowledgement is repeated so the runtime can trim and sleep").containsExactly(7L);
        verify(processor, never()).processEvent(any(), anyString(), anyString());
    }

    @Test
    void theReconcilerNeverWakesAPausedRuntimeAndMarksLostContexts() throws Exception {
        List<Long> lostAcks = new ArrayList<>();
        RecordingProgress progress = new RecordingProgress();
        admissions.reserve(OWNER, 9000L, "req-1", "a".repeat(64));
        admissions.bind(9000L, "req-1", 9001L, 9002L, "9001_9002");
        admissions.record(9000L, "req-1", DirectAdmissions.UNKNOWN, "stream lost");
        progress.open.add(admissions.find(9000L, "req-1"));
        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.empty());
        DirectChatGateway paused = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            throw new AssertionError("no turn");
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { }).withProjectionProgress(progress);
        assertThat(paused.reconcileOnce()).isZero();
        verify(bindings, never()).ensure(any());

        when(bindings.readyWithoutWake("u100.e0")).thenReturn(java.util.Optional.of(
            new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET)));
        when(processor.processEvent(any(), anyString(), eq("direct-reconcile"))).thenReturn(StreamDispatchResult.MISSING_CONTEXT);
        DirectChatGateway lost = new DirectChatGateway(routeService, bindings, new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                throw new AssertionError("no turn");
            }

            @Override
            public List<JSONObject> readOutbox(ReadyRuntime runtime, long sessionId, long after) {
                return List.of(item(1, "answerDelta"), item(2, "answerDelta"));
            }

            @Override
            public void ackOutbox(ReadyRuntime runtime, long sessionId, long throughSeq) {
                lostAcks.add(throughSeq);
            }
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { }).withProjectionProgress(progress);
        lost.reconcileOnce();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.FAILED);
        assertThat(progress.through).as("unprojectable events never block the session outbox").isEqualTo(2L);
        assertThat(lostAcks).containsExactly(2L);
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
    void anIneligibleTurnIsRejectedWithoutThrowingThroughTheRequestTransaction() throws Exception {
        when(routeService.directIneligibility(ctx)).thenReturn("This turn is not eligible for the personal HARNESS Direct route");
        ctx.setModelAnswerMessageId(9003L);
        gateway.execute(ctx, OWNER);
        verify(routeService, never()).prepareDirectGatewayRequest(any());
        verifyNoInteractions(bindings);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getString("event_type")).isEqualTo("error");
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("准入");
    }

    @Test
    void aStopWhileTheRuntimeStartsCancelsTheTurnBeforeItIsSent() throws Exception {
        runtimeOutput = event("appStreamResponse") + "\n" + line("end", "completed", null);
        List<JSONObject> stops = new ArrayList<>();
        when(bindings.ensure(OWNER)).thenAnswer(call -> {
            stops.add(gateway.stop(OWNER, 9000L, 9002L));
            return new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET);
        });
        gateway.execute(ctx, OWNER);
        assertThat(stops.get(0).getJSONArray("stopped")).containsExactly("req-1");
        assertThat(opened).as("a stopped turn is never sent after the runtime is ready").isEmpty();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.CANCELLED);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("已停止");
    }

    @Test
    void aStopWithoutAnAnswerIdCancelsTheUnsentTurnOfTheSession() throws Exception {
        List<JSONObject> stops = new ArrayList<>();
        when(bindings.ensure(OWNER)).thenAnswer(call -> {
            stops.add(gateway.stop(OWNER, 9000L, null));
            return new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET);
        });
        gateway.execute(ctx, OWNER);
        assertThat(stops.get(0).getJSONArray("stopped")).containsExactly("req-1");
        assertThat(opened).isEmpty();
    }

    @Test
    void aTurnAlreadyBeingSentIsLeftToTheRuntimeToStop() throws Exception {
        List<JSONObject> stops = new ArrayList<>();
        DirectChatGateway sending = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            stops.add(gateway.stop(OWNER, 9000L, 9002L));
            return new BufferedReader(new StringReader(line("admission", "accepted", null) + "\n"
                + event("appStreamResponse") + "\n" + line("end", "completed", null)));
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
        sending.execute(ctx, OWNER);
        assertThat(stops.get(0).getString("result")).as("the runtime decides once the turn may have arrived")
            .isEqualTo("not_running");
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.COMPLETED);
    }

    @Test
    void aRevokedScopeNeverWakesItsRuntime() throws Exception {
        gateway.withAuthority(owner -> "membership_inactive");
        gateway.execute(ctx, OWNER);
        verify(bindings, never()).ensure(any());
        assertThat(opened).isEmpty();
        assertThat(admissionRows.state(9000L, "req-1")).isEqualTo(DirectAdmissions.REJECTED);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("授权已变更");
    }

    @Test
    void authorityIsCheckedAgainAfterAColdWake() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean woke = new java.util.concurrent.atomic.AtomicBoolean();
        gateway.withAuthority(owner -> woke.get() ? "user_disabled" : null);
        when(bindings.ensure(OWNER)).thenAnswer(call -> {
            woke.set(true); // revoked while the runtime was being resumed
            return new ReadyRuntime(1L, 3L, "u100.e0", "sbx-1", "traffic", SECRET);
        });
        gateway.execute(ctx, OWNER);
        assertThat(opened).as("nothing is sent for a scope revoked during the wake").isEmpty();
        assertThat(admissionRows.rows.values().iterator().next().getDetail()).isEqualTo("scope_revoked: user_disabled");
    }

    @Test
    void aRevokedScopeCannotReadItsJobs() {
        gateway.withAuthority(owner -> "user_disabled");
        assertThat(gateway.jobs(OWNER, 9000L, null, false).getString("result")).isEqualTo("revoked");
    }

    private DirectChatGateway answering(List<JSONObject> answers, String result) {
        return new DirectChatGateway(routeService, bindings, new DirectChatGateway.TurnTransport() {
            @Override
            public BufferedReader open(ReadyRuntime runtime, DirectProtocol.SignedTurn turn) {
                opened.add("turn");
                return new BufferedReader(new StringReader(""));
            }

            @Override
            public JSONObject answerInteraction(ReadyRuntime runtime, long sessionId, String interactionId, JSONObject body) {
                JSONObject sent = new JSONObject(body);
                sent.put("interactionId", interactionId);
                answers.add(sent);
                JSONObject reply = new JSONObject();
                reply.put("result", result);
                reply.put("settledNow", "answered".equals(result));
                return reply;
            }
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
    }

    private void resume(String interactionId, String text) {
        ctx.getAssistantChatDto().setActionType(com.iwhaleai.byai.framework.core.protocol.ActionType.RESUME);
        ctx.getAssistantChatDto().setMetadata("{\"interaction_id\":\"" + interactionId + "\"}");
        ctx.getAssistantChatDto().setChatContent(text);
        ctx.continueRunningTrace = true;
    }

    @Test
    void anAnswerFromTheCardReachesTheWaitingQuestionWithoutStartingATurn() throws Exception {
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(new ReadyRuntime(1L, 3L, "u100.e0",
            "sbx-1", "traffic", SECRET)));
        List<JSONObject> answers = new ArrayList<>();
        resume("q-1", "问题 1: 是");
        answering(answers, "answered").execute(ctx, OWNER);
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getString("interactionId")).isEqualTo("q-1");
        assertThat(answers.get(0).getString("text")).isEqualTo("问题 1: 是");
        assertThat(opened).as("an answer never starts a new run").isEmpty();
        verify(bindings, never()).ensure(any());
        verify(processor, never()).processEvent(any(), anyString(), anyString());
        assertThat(admissionRows.rows).as("an answer is not a new admission").isEmpty();
    }

    @Test
    void anAnswerForAQuestionThatNoLongerWaitsEndsVisiblyAsExpired() throws Exception {
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(new ReadyRuntime(1L, 3L, "u100.e0",
            "sbx-1", "traffic", SECRET)));
        resume("q-old", "是");
        answering(new ArrayList<>(), "expired").execute(ctx, OWNER);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("已失效");
        verify(bindings, never()).ensure(any());
    }

    @Test
    void aLateAnswerToAnEndedQuestionSaysSoInsteadOfVanishing() throws Exception {
        when(bindings.readyWithoutWake(OWNER)).thenReturn(java.util.Optional.of(new ReadyRuntime(1L, 3L, "u100.e0",
            "sbx-1", "traffic", SECRET)));
        resume("q-done", "是");
        ctx.continueRunningTrace = false;
        answering(new ArrayList<>(), "cancelled").execute(ctx, OWNER);
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("已结束");
    }

    @Test
    void anAnswerToASleepingRuntimeDoesNotWakeIt() throws Exception {
        List<JSONObject> answers = new ArrayList<>();
        resume("q-1", "是");
        answering(answers, "answered").execute(ctx, OWNER);
        assertThat(answers).as("a waiting question keeps its runtime awake; a paused one has none").isEmpty();
        verify(bindings, never()).ensure(any());
    }

    @Test
    void theSignedBodyCarriesThePreparedTurnUnchanged() throws Exception {
        List<DirectProtocol.SignedTurn> turns = new ArrayList<>();
        DirectChatGateway capturing = new DirectChatGateway(routeService, bindings, (runtime, turn) -> {
            turns.add(turn);
            return new BufferedReader(new StringReader(event("appStreamResponse")));
        }, processor, outputStreamManager, registry, admissions, new DirectRuntimeProperties(), Runnable::run,
            Clock.systemUTC(), () -> { });
        capturing.execute(ctx, OWNER);
        JSONObject body = JSON.parseObject(new String(turns.get(0).body(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.getJSONObject("command").getJSONObject("body").getString("content")).isEqualTo("hello");
        assertThat(turns.get(0).signature()).isEqualTo(DirectProtocol.sign(SECRET, turns.get(0).admission()));
    }
}
