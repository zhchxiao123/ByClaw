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
        admissionRows = new InMemoryRequestAdmissions();
        admissions = new DirectAdmissions(admissionRows);
        when(routeService.prepareDirectGatewayRequest(ctx)).thenReturn(new PreparedChatGatewayRequest("BYCLAW_DSH_u1",
            "9000", "hello", "u1", "User One", "ASK_AGENT", "-1", "9002", "9001_9002", Map.of("cwd", "/by/projects/-1"),
            Map.of("requestId", "req-1")));
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
            return new BufferedReader(new StringReader(runtimeOutput));
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
        when(bindings.ensure(OWNER)).thenThrow(new RuntimeUnavailableException("provisioning", true,
            "运行资源暂时不足，已保留原会话，请稍后重试", null));
        gateway.execute(ctx, OWNER);
        assertThat(opened).isEmpty();
        ArgumentCaptor<JSONObject> events = ArgumentCaptor.forClass(JSONObject.class);
        verify(processor).processEvent(events.capture(), anyString(), anyString());
        assertThat(events.getValue().getJSONObject("metadata").getString("error")).contains("运行资源暂时不足");
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
        final List<com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission> open = new ArrayList<>();

        @Override
        public long throughSeq(long sessionId) {
            return through;
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
