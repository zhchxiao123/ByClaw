package com.iwhalecloud.byai.gateway.direct;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.DirectAdmissions.Kind;
import com.iwhalecloud.byai.gateway.direct.DirectAdmissions.Reservation;
import com.iwhalecloud.byai.gateway.direct.DirectProtocol.SignedTurn;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.RuntimeUnavailableException;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;
import com.iwhalecloud.byai.gateway.route.PreparedChatGatewayRequest;
import com.iwhalecloud.byai.common.i18n.I18nUtil;
import com.iwhalecloud.byai.gateway.route.RouteService;
import com.iwhalecloud.byai.state.infrastructure.common.constants.SseResponseEventEnum;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.state.domain.chat.service.OutputStreamManager;
import com.iwhalecloud.byai.state.domain.chat.service.RunningOutputStreamRegistry;
import com.iwhalecloud.byai.state.domain.chat.service.StreamDispatchResult;
import com.iwhalecloud.byai.state.domain.chat.service.StreamRecordProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * Executes a Direct HARNESS turn: platform preparation, private runtime ensure, signed admission
 * and the runtime's NDJSON output fed into the same projection pipeline as Legacy events.
 * Chat commands and output do not pass through a Worker, Redis Stream or platform broadcast.
 * Every request is recorded in {@link DirectAdmissions} so a lost response can be queried and a
 * repeated request never starts a second run.
 */
@Slf4j
public class DirectChatGateway {

    /** Opens the admitted turn on the runtime; yields NDJSON lines until the runtime ends the turn. */
    public interface TurnTransport {
        BufferedReader open(ReadyRuntime runtime, SignedTurn turn) throws IOException;

        /** Runtime-side state of an admitted request, when the runtime is running and answers. */
        default Optional<String> turnState(ReadyRuntime runtime, long sessionId, String requestId) {
            return Optional.empty();
        }
    }

    /** The runtime answered the admission with a definite refusal (nothing was accepted). */
    public static class RuntimeRefusedException extends IOException {
        private final int status;
        private final String error;

        public RuntimeRefusedException(int status, String error) {
            super("Direct runtime refused the turn: HTTP " + status + " " + error);
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    /** Crash window for isolated acceptance only: the backend halts after reserving, before dispatch. */
    public static final String FAULT_HALT_BEFORE_DISPATCH = "halt-before-dispatch";

    private final RouteService routeService;
    private final RuntimeBindingService bindings;
    private final TurnTransport transport;
    private final StreamRecordProcessor processor;
    private final OutputStreamManager outputStreamManager;
    private final RunningOutputStreamRegistry runningRegistry;
    private final DirectAdmissions admissions;
    private final DirectRuntimeProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final Runnable halt;
    private final Map<Long, long[]> streamClocks = new ConcurrentHashMap<>();

    public DirectChatGateway(RouteService routeService, RuntimeBindingService bindings, TurnTransport transport,
                             StreamRecordProcessor processor, OutputStreamManager outputStreamManager,
                             RunningOutputStreamRegistry runningRegistry, DirectAdmissions admissions,
                             DirectRuntimeProperties properties, Executor executor, Clock clock, Runnable halt) {
        this.routeService = routeService;
        this.bindings = bindings;
        this.transport = transport;
        this.processor = processor;
        this.outputStreamManager = outputStreamManager;
        this.runningRegistry = runningRegistry;
        this.admissions = admissions;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.halt = halt;
    }

    private record Turn(ChatProcessContext ctx, RuntimeOwnerScope owner, String requestId, String intentDigest) {
    }

    /**
     * Prepare on the request thread (authorisation, model, quota and scope guards need the login),
     * then ensure the runtime and stream the turn asynchronously.
     */
    public void execute(ChatProcessContext ctx, RuntimeOwnerScope owner) {
        AssistantChatDto dto = ctx.getAssistantChatDto();
        String intentDigest = DirectIntent.digest(dto);
        PreparedChatGatewayRequest prepared;
        String rejection = null;
        try {
            prepared = routeService.prepareDirectGatewayRequest(ctx);
        }
        catch (IllegalArgumentException e) {
            // The session is already Direct: never fall back to the Worker. End this turn visibly
            // after the request transaction commits, without contacting the runtime.
            log.warn("Direct turn rejected during preparation, sessionId={}: {}", ctx.sessionId, e.getMessage());
            prepared = null;
            rejection = "本轮请求不符合 Direct 会话的准入条件，已拒绝执行";
        }
        String requestId = prepared == null ? DirectIntent.requestId(dto)
            : String.valueOf(prepared.metadata().get("requestId"));
        if (rejection == null && dto.getDirectReservedRequestId() == null) {
            // First turn of a new session: the gate could not reserve before the session existed.
            Reservation reservation = admissions.reserve(owner, ctx.sessionId, requestId, intentDigest);
            if (reservation.kind() != Kind.NEW) {
                rejection = reservation.kind() == Kind.CONFLICT ? "请求编号已用于不同的内容，已拒绝"
                    : DirectAdmissions.describe(reservation.admission());
                requestId = null;
            }
        }
        if (requestId != null && rejection == null) {
            admissions.bind(ctx.sessionId, requestId, ctx.getUserMessageId(), ctx.getModelAnswerMessageId(), ctx.traceId);
        }
        ctx.directTransport = true;
        ctx.asyncResponse = true;
        String sessionKey = String.valueOf(ctx.sessionId);
        outputStreamManager.withSessionLock(sessionKey, () -> {
            outputStreamManager.putContext(sessionKey, ctx);
            runningRegistry.markRunning(ctx);
            return null;
        });
        Turn turn = new Turn(ctx, owner, requestId, intentDigest);
        if (rejection != null) {
            String message = rejection;
            if (requestId != null && prepared == null && dto.getDirectReservedRequestId() != null) {
                admissions.record(ctx.sessionId, requestId, DirectAdmissions.REJECTED, "preparation refused");
            }
            executor.execute(() -> finishWithError(ctx, ctx.targetAgentType, String.valueOf(ctx.modelAnswerMessageId), message));
            return;
        }
        PreparedChatGatewayRequest admitted = prepared;
        // User-facing progress and errors keep the language of the request that started the turn.
        java.util.Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        executor.execute(() -> {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
            try {
                run(turn, admitted);
            }
            finally {
                org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
            }
        });
    }

    void run(Turn turn, PreparedChatGatewayRequest prepared) {
        ChatProcessContext ctx = turn.ctx();
        boolean terminal = false;
        boolean accepted = false;
        String endState = null;
        try {
            // Creating or resuming the environment is shown as preparation, never as a queue position.
            boolean cold = bindings.readyWithoutWake(turn.owner()).isEmpty();
            if (cold) routeService.sendRuntimeProgress(ctx, SseResponseEventEnum.reasoningLogStart,
                message("sandbox.launch.progress.start", "个人助理正在启动中，请等待"));
            ReadyRuntime runtime = bindings.ensure(turn.owner());
            if (cold) routeService.sendRuntimeProgress(ctx, SseResponseEventEnum.reasoningLogEnd,
                message("sandbox.launch.progress.ready", "个人助理已启动，正在处理请求"));
            SignedTurn signed = DirectProtocol.signTurn(prepared, runtime.ownerScope(), runtime.generation(),
                runtime.runtimeSecret(), clock.millis(), properties.getAdmissionTtlSeconds(), turn.intentDigest());
            if (properties.isFaultAcceptance() && FAULT_HALT_BEFORE_DISPATCH.equals(properties.getFault())) halt.run();
            try (BufferedReader lines = transport.open(runtime, signed)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JSONObject message = JSON.parseObject(line);
                    String type = message.getString("type");
                    if ("event".equals(type)) {
                        terminal |= dispatch(ctx, message.getJSONObject("data"));
                    }
                    else if ("end".equals(type)) {
                        endState = message.getString("state");
                    }
                    else if ("admission".equals(type)) {
                        // The runtime durably accepted (or already held) this request.
                        accepted = true;
                        admissions.record(ctx.sessionId, turn.requestId(), DirectAdmissions.ACCEPTED,
                            "runtime " + message.getString("state"));
                    }
                }
            }
            admissions.record(ctx.sessionId, turn.requestId(), finalState(endState), "runtime end " + endState);
            if (!terminal) finishWithError(ctx, prepared, endMessage(endState));
        }
        catch (RuntimeUnavailableException e) {
            log.warn("Direct runtime unavailable, sessionId={}, state={}, retryable={}", ctx.sessionId, e.state(), e.retryable());
            admissions.record(ctx.sessionId, turn.requestId(), DirectAdmissions.REJECTED, "runtime " + e.state());
            if (!terminal) finishWithError(ctx, prepared, e.getMessage());
        }
        catch (RuntimeRefusedException e) {
            log.warn("Direct runtime refused the turn, sessionId={}, status={}, error={}", ctx.sessionId, e.status(), e.error());
            boolean conflict = e.status() == 409;
            admissions.record(ctx.sessionId, turn.requestId(), DirectAdmissions.REJECTED, "runtime refused " + e.error());
            if (!terminal) finishWithError(ctx, prepared, conflict ? "请求编号已用于不同的内容，已拒绝"
                : "storage_unavailable".equals(e.error()) ? "运行环境存储不可写，本轮未被接纳，请稍后重试"
                : "运行环境拒绝了本轮准入，未执行");
        }
        catch (Exception e) {
            // The runtime may still be executing; the outcome is unknown and is never re-sent automatically.
            log.warn("Direct turn stream failed, sessionId={}, traceId={}, errorType={}", ctx.sessionId, ctx.traceId,
                e.getClass().getSimpleName());
            admissions.record(ctx.sessionId, turn.requestId(), accepted ? DirectAdmissions.UNKNOWN : DirectAdmissions.DISPATCHING,
                "stream lost " + e.getClass().getSimpleName());
            if (!terminal) finishWithError(ctx, prepared, "与运行环境的连接中断，本轮结果未知，请勿重复发送");
        }
    }

    /** Runtime-side state for a status query; paused or absent runtimes are not woken. */
    public Optional<String> runtimeTurnState(RuntimeOwnerScope owner, RequestAdmission admission) {
        return bindings.readyWithoutWake(owner)
            .flatMap(runtime -> transport.turnState(runtime, admission.getSessionId(), admission.getRequestId()));
    }

    private static String message(String key, String fallback) {
        try {
            String value = I18nUtil.get(key);
            return StringUtils.isBlank(value) || key.equals(value) ? fallback : value;
        }
        catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String finalState(String endState) {
        if ("completed".equals(endState)) return DirectAdmissions.COMPLETED;
        if ("failed".equals(endState) || "cancelled".equals(endState)) return DirectAdmissions.FAILED;
        if ("running".equals(endState) || "accepted".equals(endState)) return DirectAdmissions.ACCEPTED;
        return DirectAdmissions.UNKNOWN;
    }

    private boolean dispatch(ChatProcessContext ctx, JSONObject event) {
        if (event == null) return false;
        // Events of another session or trace cannot be applied to this turn.
        if (!String.valueOf(ctx.sessionId).equals(event.getString("session_id"))
            || !StringUtils.equals(ctx.traceId, event.getString("trace_id"))) {
            log.warn("Direct event outside the admitted turn ignored, sessionId={}", ctx.sessionId);
            return false;
        }
        StreamDispatchResult result = processor.processEvent(event, nextStreamId(ctx.sessionId), "direct");
        if (result.isTerminal()) processor.afterAcknowledge(result);
        return result.isTerminal();
    }

    private void finishWithError(ChatProcessContext ctx, PreparedChatGatewayRequest prepared, String message) {
        finishWithError(ctx, prepared.targetAgentType(), prepared.messageId(), message);
    }

    private void finishWithError(ChatProcessContext ctx, String targetAgentType, String messageId, String message) {
        JSONObject error = new JSONObject(true);
        error.put("trace_id", ctx.traceId);
        error.put("session_id", String.valueOf(ctx.sessionId));
        error.put("event_type", "error");
        error.put("source_agent_type", StringUtils.defaultString(targetAgentType));
        error.put("message_id", messageId);
        error.put("parent_message_id", "-1");
        error.put("timestamp", clock.millis());
        error.put("data", new JSONObject());
        JSONObject metadata = new JSONObject();
        metadata.put("error", message);
        error.put("metadata", metadata);
        try {
            dispatch(ctx, error);
        }
        catch (RuntimeException e) {
            log.warn("Direct terminal error projection failed, sessionId={}", ctx.sessionId, e);
        }
    }

    private static String endMessage(String state) {
        if ("completed".equals(state)) return "运行环境已结束本轮但未返回终态，请刷新后查看";
        if ("running".equals(state)) return "该请求仍在执行，请稍后刷新查看结果";
        if ("unknown".equals(state)) return "本轮执行结果未知，请勿重复发送";
        return "本轮执行失败";
    }

    /** Monotonic Redis-compatible ids (ms-seq) so replay watermarks keep working per session. */
    private String nextStreamId(Long sessionId) {
        long[] state = streamClocks.computeIfAbsent(sessionId, key -> new long[] {0L, 0L});
        synchronized (state) {
            long now = clock.millis();
            if (now > state[0]) {
                state[0] = now;
                state[1] = 0L;
            }
            else {
                state[1]++;
            }
            return state[0] + "-" + state[1];
        }
    }
}
