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

        /** Unacknowledged outbox items ({seq, event}) after a projected position. */
        default java.util.List<JSONObject> readOutbox(ReadyRuntime runtime, long sessionId, long after) throws IOException {
            return java.util.List.of();
        }

        /** Acknowledge projection through a sequence; the runtime may then trim and sleep. */
        default void ackOutbox(ReadyRuntime runtime, long sessionId, long throughSeq) throws IOException {
        }

        /** stop / clear-queue on the runtime; returns {stopped, cleared, states}. */
        default JSONObject control(ReadyRuntime runtime, long sessionId, String action, String requestId) throws IOException {
            throw new IOException("Direct control unavailable");
        }

        /** Signed GET/POST of a runtime session resource (jobs); returns the parsed JSON body. */
        default JSONObject sessionResource(ReadyRuntime runtime, String method, String path) throws IOException {
            throw new IOException("Direct session resources unavailable");
        }

        /** Sessions with unacknowledged outbox events on a running runtime. */
        default Map<Long, Long> pendingProjection(ReadyRuntime runtime) throws IOException {
            return Map.of();
        }
    }

    /** Platform projection position per Direct session. */
    public interface ProjectionProgress {
        long throughSeq(long sessionId);

        void advance(long sessionId, String ownerScope, long throughSeq);

        java.util.List<RequestAdmission> openAdmissions(int settleSeconds, int limit);

        /** Owner scopes whose runtime binding is ready; the reconciler only talks to running ones. */
        default java.util.List<String> readyOwners() {
            return java.util.List.of();
        }

        ProjectionProgress NONE = new ProjectionProgress() {
            @Override
            public long throughSeq(long sessionId) {
                return 0L;
            }

            @Override
            public void advance(long sessionId, String ownerScope, long throughSeq) {
            }

            @Override
            public java.util.List<RequestAdmission> openAdmissions(int settleSeconds, int limit) {
                return java.util.List.of();
            }
        };
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

    /** Crash window for isolated acceptance only: the backend halts after projecting, before acknowledging. */
    public static final String FAULT_HALT_BEFORE_ACK = "halt-before-ack";

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
    private RuntimeScopeAuthority authority = RuntimeScopeAuthority.ALWAYS;
    private ProjectionProgress progress = ProjectionProgress.NONE;
    private final Map<Long, long[]> streamClocks = new ConcurrentHashMap<>();
    /** Turns whose live stream is being consumed by this instance; the reconciler leaves them alone. */
    private final java.util.Set<String> liveTurns = ConcurrentHashMap.newKeySet();

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

    public DirectChatGateway withAuthority(RuntimeScopeAuthority authority) {
        this.authority = authority;
        return this;
    }

    public DirectChatGateway withProjectionProgress(ProjectionProgress progress) {
        this.progress = progress;
        return this;
    }

    /**
     * Prepare on the request thread (authorisation, model, quota and scope guards need the login),
     * then ensure the runtime and stream the turn asynchronously.
     */
    public void execute(ChatProcessContext ctx, RuntimeOwnerScope owner) {
        AssistantChatDto dto = ctx.getAssistantChatDto();
        String intentDigest = DirectIntent.digest(dto);
        PreparedChatGatewayRequest prepared = null;
        String rejection = null;
        // The session is already Direct: never fall back to the Worker. An ineligible turn ends
        // visibly after the request transaction commits, without contacting the runtime. Asking
        // first keeps the user message: a throw through the route service marks it rollback-only.
        String ineligible = routeService.directIneligibility(ctx);
        try {
            if (ineligible == null) prepared = routeService.prepareDirectGatewayRequest(ctx);
        }
        catch (IllegalArgumentException e) {
            ineligible = e.getMessage();
        }
        if (prepared == null) {
            log.warn("Direct turn rejected during preparation, sessionId={}: {}", ctx.sessionId, ineligible);
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
        long lastSeq = 0L;
        ReadyRuntime runtime = null;
        String live = ctx.sessionId + ":" + ctx.traceId;
        liveTurns.add(live);
        try {
            if (revoked(turn, "before wake")) return;
            // Creating or resuming the environment is shown as preparation, never as a queue position.
            boolean cold = bindings.readyWithoutWake(turn.owner()).isEmpty();
            if (cold) routeService.sendRuntimeProgress(ctx, SseResponseEventEnum.reasoningLogStart,
                message("sandbox.launch.progress.start", "个人助理正在启动中，请等待"));
            runtime = bindings.ensure(turn.owner());
            if (cold) routeService.sendRuntimeProgress(ctx, SseResponseEventEnum.reasoningLogEnd,
                message("sandbox.launch.progress.ready", "个人助理已启动，正在处理请求"));
            if (properties.isFaultAcceptance() && FAULT_HALT_BEFORE_DISPATCH.equals(properties.getFault())) halt.run();
            // A wake can take long enough for access to be revoked meanwhile: ask again before sending.
            if (cold && revoked(turn, "after wake")) return;
            if (!claimSend(turn)) return;
            BufferedReader opened;
            try {
                opened = transport.open(runtime, sign(prepared, runtime, turn));
            }
            catch (RuntimeRefusedException e) {
                if (!"draining".equals(e.error())) throw e;
                // The runtime closed admission for a pause before this turn arrived; nothing was
                // admitted, so it is safe to wait for the pause, resume and send once more.
                admissions.returnUnsent(ctx.sessionId, turn.requestId());
                runtime = bindings.ensure(turn.owner());
                if (!claimSend(turn)) return;
                opened = transport.open(runtime, sign(prepared, runtime, turn));
            }
            try (BufferedReader lines = opened) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JSONObject message = JSON.parseObject(line);
                    String type = message.getString("type");
                    if ("event".equals(type)) {
                        Long seq = message.getLong("seq");
                        terminal |= dispatch(ctx, message.getJSONObject("data"), seq);
                        if (seq != null) lastSeq = Math.max(lastSeq, seq);
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
            settle(runtime, ctx.sessionId, turn.owner().key(), lastSeq);
        }
        catch (RuntimeUnavailableException e) {
            log.warn("Direct runtime unavailable, sessionId={}, state={}, fault={}, retryable={}", ctx.sessionId, e.state(),
                e.fault().code(), e.retryable());
            admissions.record(ctx.sessionId, turn.requestId(), DirectAdmissions.REJECTED, "runtime " + e.fault().code());
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
            admissions.record(ctx.sessionId, turn.requestId(), accepted ? DirectAdmissions.UNKNOWN : DirectAdmissions.SENDING,
                accepted ? "stream lost, reconciling from outbox" : "stream lost " + e.getClass().getSimpleName());
            // An accepted turn stays open: its events are durable in the runtime outbox and the
            // reconciler projects them. Only a turn the runtime never confirmed ends here.
            if (!terminal && !accepted) finishWithError(ctx, prepared, "与运行环境的连接中断，本轮结果未知，请勿重复发送");
        }
        finally {
            liveTurns.remove(live);
        }
    }

    /** End the turn without sending when its owner scope may no longer act; audited in the log. */
    private boolean revoked(Turn turn, String when) {
        String reason = authority.revoked(turn.owner().key());
        if (reason == null) return false;
        ChatProcessContext ctx = turn.ctx();
        log.warn("Direct turn refused, owner scope revoked {}, owner={}, sessionId={}, requestId={}, reason={}", when,
            turn.owner().key(), ctx.sessionId, turn.requestId(), reason);
        if (turn.requestId() != null) {
            admissions.record(ctx.sessionId, turn.requestId(), DirectAdmissions.REJECTED, "scope_revoked: " + reason);
        }
        finishWithError(ctx, ctx.targetAgentType, String.valueOf(ctx.modelAnswerMessageId), "账号或企业授权已变更，本轮未执行");
        return true;
    }

    /**
     * Stop the work of a revoked owner scope: unsent turns are cancelled, running ones are stopped
     * on the runtime. Other scopes are untouched.
     * @return request ids stopped or cancelled.
     */
    public java.util.List<String> stopOwnerWork(String ownerKey) {
        java.util.List<String> stopped = new java.util.ArrayList<>();
        java.util.Set<Long> sessions = new java.util.LinkedHashSet<>();
        for (RequestAdmission open : progress.openAdmissions(0, 500)) {
            if (!ownerKey.equals(open.getOwnerScope())) continue;
            if (DirectAdmissions.DISPATCHING.equals(open.getState())
                && admissions.cancelUnsent(open.getSessionId(), open.getRequestId())) {
                stopped.add(open.getRequestId());
            }
            else {
                sessions.add(open.getSessionId());
            }
        }
        Optional<ReadyRuntime> runtime = bindings.readyWithoutWake(ownerKey);
        for (Long sessionId : sessions) {
            if (runtime.isEmpty()) break;
            try {
                JSONObject result = transport.control(runtime.get(), sessionId, "stop", null);
                stream(result, "stopped").forEach(stopped::add);
            }
            catch (IOException e) {
                log.warn("Direct stop for a revoked scope failed, owner={}, sessionId={}", ownerKey, sessionId);
            }
        }
        return stopped;
    }

    /**
     * Claim the turn for sending. A stop that arrived while the runtime was being created or resumed
     * cancelled it first: end it as stopped without contacting the runtime.
     */
    private boolean claimSend(Turn turn) {
        ChatProcessContext ctx = turn.ctx();
        if (turn.requestId() == null || admissions.claimSend(ctx.sessionId, turn.requestId())) return true;
        RequestAdmission current = admissions.find(ctx.sessionId, turn.requestId());
        if (current != null && !DirectAdmissions.CANCELLED.equals(current.getState())) return true;
        log.info("Direct turn stopped before dispatch, sessionId={}, requestId={}", ctx.sessionId, turn.requestId());
        finishWithError(ctx, ctx.targetAgentType, String.valueOf(ctx.modelAnswerMessageId), "本轮已停止，未执行");
        return false;
    }

    private SignedTurn sign(PreparedChatGatewayRequest prepared, ReadyRuntime runtime, Turn turn) {
        return DirectProtocol.signTurn(prepared, runtime.ownerScope(), runtime.generation(), runtime.runtimeSecret(),
            clock.millis(), properties.getAdmissionTtlSeconds(), turn.intentDigest());
    }

    /** Record the projected position and let the runtime trim its outbox. */
    private void settle(ReadyRuntime runtime, Long sessionId, String ownerScope, long throughSeq) {
        if (runtime == null || throughSeq <= 0L) return;
        try {
            progress.advance(sessionId, ownerScope, throughSeq);
            if (properties.isFaultAcceptance() && FAULT_HALT_BEFORE_ACK.equals(properties.getFault())) halt.run();
            transport.ackOutbox(runtime, sessionId, throughSeq);
        }
        catch (Exception e) {
            // Unacknowledged events stay in the runtime outbox; the reconciler acknowledges later.
            log.warn("Direct projection acknowledgement deferred, sessionId={}, throughSeq={}", sessionId, throughSeq);
        }
    }

    /**
     * Project runtime outbox events the platform missed (stream lost, backend restarted) for open
     * admissions, then acknowledge them. Idempotent: positions, stream-id watermarks and terminal
     * persistence markers prevent duplicate content. Paused runtimes are not woken.
     */
    public int reconcileOnce() {
        int projected = 0;
        java.util.Set<Long> visited = new java.util.HashSet<>();
        // 1. Every running runtime names the sessions whose events the platform has not acknowledged.
        for (String ownerScope : progress.readyOwners()) {
            Optional<ReadyRuntime> runtime = bindings.readyWithoutWake(ownerScope);
            if (runtime.isEmpty()) continue;
            try {
                for (Long sessionId : transport.pendingProjection(runtime.get()).keySet()) {
                    if (isLive(sessionId)) continue;
                    visited.add(sessionId);
                    projected += reconcileSession(runtime.get(), sessionId, ownerScope);
                }
            }
            catch (Exception e) {
                log.warn("Direct projection sweep failed, owner={}, errorType={}", ownerScope, e.getClass().getSimpleName());
            }
        }
        // 2. Open admissions are settled from the runtime's own turn state once nothing is left to project.
        for (RequestAdmission admission : progress.openAdmissions(15, 50)) {
            if (liveTurns.contains(admission.getSessionId() + ":" + admission.getTraceId())) continue;
            Optional<ReadyRuntime> runtime = bindings.readyWithoutWake(admission.getOwnerScope());
            if (runtime.isEmpty()) continue;
            try {
                if (visited.add(admission.getSessionId())) {
                    projected += reconcileSession(runtime.get(), admission.getSessionId(), admission.getOwnerScope());
                }
                RequestAdmission current = admissions.find(admission.getSessionId(), admission.getRequestId());
                if (current != null && DirectAdmissions.OPEN.contains(current.getState())) settle(runtime.get(), current);
            }
            catch (Exception e) {
                log.warn("Direct projection reconcile failed, sessionId={}, errorType={}", admission.getSessionId(),
                    e.getClass().getSimpleName());
            }
        }
        return projected;
    }

    private boolean isLive(Long sessionId) {
        String prefix = sessionId + ":";
        return liveTurns.stream().anyMatch(key -> key.startsWith(prefix));
    }

    private int reconcileSession(ReadyRuntime runtime, long sessionId, String ownerScope) throws IOException {
        long after = progress.throughSeq(sessionId);
        java.util.List<JSONObject> items = transport.readOutbox(runtime, sessionId, after);
        long through = after;
        java.util.Set<String> lostTraces = new java.util.HashSet<>();
        for (JSONObject item : items) {
            long seq = item.getLongValue("seq");
            through = seq;
            JSONObject event = item.getJSONObject("event");
            if (event == null) continue;
            StreamDispatchResult result = processor.processEvent(event, seq + "-0", "direct-reconcile");
            if (result == StreamDispatchResult.MISSING_CONTEXT) {
                // This event has no platform turn to apply to (its context expired, or it was never a
                // platform turn). Skip it visibly; it must not block later events of the session.
                if (lostTraces.add(String.valueOf(event.getString("trace_id")))) markContextLost(sessionId, event.getString("trace_id"));
                continue;
            }
            if (result.isTerminal()) {
                processor.afterAcknowledge(result);
                settleTrace(sessionId, event);
            }
        }
        if (!lostTraces.isEmpty()) {
            log.warn("Direct reconcile skipped events without a platform turn, sessionId={}, traces={}", sessionId,
                lostTraces.size());
        }
        if (through > after) progress.advance(sessionId, ownerScope, through);
        if (properties.isFaultAcceptance() && FAULT_HALT_BEFORE_ACK.equals(properties.getFault())) halt.run();
        // Re-acknowledge an already projected position too: an earlier ack may have been lost.
        if (through > 0) transport.ackOutbox(runtime, sessionId, through);
        return items.size();
    }

    private void settleTrace(long sessionId, JSONObject terminal) {
        String requestId = admissionRequestId(sessionId, terminal.getString("trace_id"));
        if (requestId == null) return;
        admissions.record(sessionId, requestId,
            "error".equals(terminal.getString("event_type")) ? DirectAdmissions.FAILED : DirectAdmissions.COMPLETED,
            "reconciled from outbox");
    }

    private void markContextLost(long sessionId, String traceId) {
        String requestId = admissionRequestId(sessionId, traceId);
        if (requestId != null) admissions.record(sessionId, requestId, DirectAdmissions.FAILED, "projection context lost");
    }

    private String admissionRequestId(long sessionId, String traceId) {
        for (RequestAdmission open : progress.openAdmissions(0, 200)) {
            if (open.getSessionId() == sessionId && StringUtils.equals(open.getTraceId(), traceId)) return open.getRequestId();
        }
        return null;
    }

    /** Close an open admission from the runtime's record when its events were already projected. */
    private void settle(ReadyRuntime runtime, RequestAdmission admission) {
        transport.turnState(runtime, admission.getSessionId(), admission.getRequestId()).ifPresent(state -> {
            switch (state) {
                case "completed" -> admissions.record(admission.getSessionId(), admission.getRequestId(),
                    DirectAdmissions.COMPLETED, "runtime record completed");
                case "failed" -> admissions.record(admission.getSessionId(), admission.getRequestId(),
                    DirectAdmissions.FAILED, "runtime record failed");
                case "cancelled" -> admissions.record(admission.getSessionId(), admission.getRequestId(),
                    DirectAdmissions.CANCELLED, "runtime record cancelled");
                case "not_admitted" -> {
                    if (DirectAdmissions.DISPATCHING.equals(admission.getState())
                        || DirectAdmissions.SENDING.equals(admission.getState())) {
                        admissions.record(admission.getSessionId(), admission.getRequestId(), DirectAdmissions.REJECTED,
                            "runtime never admitted");
                    }
                }
                default -> {
                    // running / accepted / unknown stay open and visible.
                }
            }
        });
    }

    /**
     * Stop the executing turn of a Direct session, or the turn of {@code answerMessageId} even if it is
     * still queued. Queued inputs stay. A paused or absent runtime has nothing running and is not woken.
     */
    public JSONObject stop(RuntimeOwnerScope owner, long sessionId, Long answerMessageId) {
        String requestId = null;
        RequestAdmission target;
        if (answerMessageId != null) {
            target = admissions.findByAnswer(sessionId, answerMessageId);
            if (target == null) return controlResult(sessionId, "stop", "not_found");
            if (!DirectAdmissions.OPEN.contains(target.getState())) return controlResult(sessionId, "stop", target.getState());
            requestId = target.getRequestId();
        }
        else {
            target = admissions.firstUnsent(sessionId);
        }
        // A turn waiting for its runtime to be created or resumed has not been sent: cancel it here
        // so it never runs after the wake. Once it is being sent only the runtime can stop it.
        if (target != null && DirectAdmissions.DISPATCHING.equals(target.getState())
            && admissions.cancelUnsent(sessionId, target.getRequestId())) {
            log.info("Direct stop cancelled an unsent turn, sessionId={}, requestId={}", sessionId, target.getRequestId());
            JSONObject result = controlResult(sessionId, "stop", "cancelled_before_dispatch");
            result.put("stopped", java.util.List.of(target.getRequestId()));
            return result;
        }
        return control(owner, sessionId, "stop", requestId);
    }

    /**
     * Background jobs of a Direct session: list, one job (settled output only) or kill. Jobs run only
     * while the runtime runs; a paused runtime is reported as such and never woken for a read.
     */
    public JSONObject jobs(RuntimeOwnerScope owner, long sessionId, String jobId, boolean kill) {
        if (authority.revoked(owner.key()) != null) return controlResult(sessionId, kill ? "job-kill" : "jobs", "revoked");
        Optional<ReadyRuntime> runtime = bindings.readyWithoutWake(owner);
        if (runtime.isEmpty()) return controlResult(sessionId, kill ? "job-kill" : "jobs", "not_running");
        if (jobId != null && !jobId.matches("[A-Za-z0-9_.:-]{1,64}")) throw new IllegalArgumentException("invalid job id");
        String path = DirectProtocol.ROUTE_PREFIX + "/sessions/" + sessionId + "/jobs"
            + (jobId == null ? "" : "/" + jobId + (kill ? "/kill" : ""));
        try {
            return transport.sessionResource(runtime.get(), kill ? "POST" : "GET", path);
        }
        catch (IOException e) {
            return controlResult(sessionId, kill ? "job-kill" : "jobs", e.getMessage().contains("404") ? "not_found" : "unavailable");
        }
    }

    /** Remove admitted inputs that have not started; the executing turn continues. */
    public JSONObject clearQueue(RuntimeOwnerScope owner, long sessionId) {
        return control(owner, sessionId, "clear-queue", null);
    }

    private JSONObject control(RuntimeOwnerScope owner, long sessionId, String action, String requestId) {
        Optional<ReadyRuntime> runtime = bindings.readyWithoutWake(owner);
        if (runtime.isEmpty()) return controlResult(sessionId, action, "not_running");
        try {
            JSONObject result = transport.control(runtime.get(), sessionId, action, requestId);
            for (String id : java.util.stream.Stream.concat(stream(result, "stopped"), stream(result, "cleared")).toList()) {
                admissions.record(sessionId, id, DirectAdmissions.ACCEPTED, action + " requested");
            }
            log.info("Direct {} for sessionId={}: stopped={} cleared={}", action, sessionId, result.get("stopped"),
                result.get("cleared"));
            return result;
        }
        catch (IOException e) {
            log.warn("Direct {} failed, sessionId={}, errorType={}", action, sessionId, e.getClass().getSimpleName());
            return controlResult(sessionId, action, "unavailable");
        }
    }

    private static java.util.stream.Stream<String> stream(JSONObject result, String key) {
        com.alibaba.fastjson.JSONArray values = result == null ? null : result.getJSONArray(key);
        return values == null ? java.util.stream.Stream.empty() : values.stream().map(String::valueOf);
    }

    private static JSONObject controlResult(long sessionId, String action, String state) {
        JSONObject result = new JSONObject(true);
        result.put("sessionId", String.valueOf(sessionId));
        result.put("action", action);
        result.put("stopped", java.util.List.of());
        result.put("cleared", java.util.List.of());
        result.put("result", state);
        return result;
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
        if ("cancelled".equals(endState)) return DirectAdmissions.CANCELLED;
        if ("failed".equals(endState)) return DirectAdmissions.FAILED;
        if ("running".equals(endState) || "accepted".equals(endState)) return DirectAdmissions.ACCEPTED;
        return DirectAdmissions.UNKNOWN;
    }

    private boolean dispatch(ChatProcessContext ctx, JSONObject event) {
        return dispatch(ctx, event, null);
    }

    private boolean dispatch(ChatProcessContext ctx, JSONObject event, Long seq) {
        if (event == null) return false;
        // Events of another session or trace cannot be applied to this turn.
        if (!String.valueOf(ctx.sessionId).equals(event.getString("session_id"))
            || !StringUtils.equals(ctx.traceId, event.getString("trace_id"))) {
            log.warn("Direct event outside the admitted turn ignored, sessionId={}", ctx.sessionId);
            return false;
        }
        // Runtime outbox sequences are stable across replays, so watermarks and terminal markers dedupe.
        String streamId = seq == null ? nextStreamId(ctx.sessionId) : seq + "-0";
        StreamDispatchResult result = processor.processEvent(event, streamId, "direct");
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
