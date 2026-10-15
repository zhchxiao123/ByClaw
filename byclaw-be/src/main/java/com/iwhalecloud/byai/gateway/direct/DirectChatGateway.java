package com.iwhalecloud.byai.gateway.direct;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.DirectProtocol.SignedTurn;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.RuntimeUnavailableException;
import com.iwhalecloud.byai.gateway.route.PreparedChatGatewayRequest;
import com.iwhalecloud.byai.gateway.route.RouteService;
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
 */
@Slf4j
public class DirectChatGateway {

    /** Opens the admitted turn on the runtime; yields NDJSON lines until the runtime ends the turn. */
    public interface TurnTransport {
        BufferedReader open(ReadyRuntime runtime, SignedTurn turn) throws IOException;
    }

    private final RouteService routeService;
    private final RuntimeBindingService bindings;
    private final TurnTransport transport;
    private final StreamRecordProcessor processor;
    private final OutputStreamManager outputStreamManager;
    private final RunningOutputStreamRegistry runningRegistry;
    private final DirectRuntimeProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final Map<Long, long[]> streamClocks = new ConcurrentHashMap<>();

    public DirectChatGateway(RouteService routeService, RuntimeBindingService bindings, TurnTransport transport,
                             StreamRecordProcessor processor, OutputStreamManager outputStreamManager,
                             RunningOutputStreamRegistry runningRegistry, DirectRuntimeProperties properties,
                             Executor executor, Clock clock) {
        this.routeService = routeService;
        this.bindings = bindings;
        this.transport = transport;
        this.processor = processor;
        this.outputStreamManager = outputStreamManager;
        this.runningRegistry = runningRegistry;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Prepare on the request thread (authorisation, model, quota and scope guards need the login),
     * then ensure the runtime and stream the turn asynchronously.
     */
    public void execute(ChatProcessContext ctx, RuntimeOwnerScope owner) {
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
        ctx.directTransport = true;
        ctx.asyncResponse = true;
        String sessionKey = String.valueOf(ctx.sessionId);
        outputStreamManager.withSessionLock(sessionKey, () -> {
            outputStreamManager.putContext(sessionKey, ctx);
            runningRegistry.markRunning(ctx);
            return null;
        });
        if (rejection != null) {
            String message = rejection;
            executor.execute(() -> finishWithError(ctx, ctx.targetAgentType, String.valueOf(ctx.modelAnswerMessageId), message));
            return;
        }
        PreparedChatGatewayRequest admitted = prepared;
        executor.execute(() -> run(ctx, owner, admitted));
    }

    void run(ChatProcessContext ctx, RuntimeOwnerScope owner, PreparedChatGatewayRequest prepared) {
        boolean terminal = false;
        String endState = null;
        try {
            ReadyRuntime runtime = bindings.ensure(owner);
            SignedTurn turn = DirectProtocol.signTurn(prepared, runtime.ownerScope(), runtime.generation(),
                runtime.runtimeSecret(), clock.millis(), properties.getAdmissionTtlSeconds());
            try (BufferedReader lines = transport.open(runtime, turn)) {
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
                    else if ("admission".equals(type) && "duplicate".equals(message.getString("state"))) {
                        log.info("Direct admission was already accepted, sessionId={}, traceId={}", ctx.sessionId, ctx.traceId);
                    }
                }
            }
            if (!terminal) {
                finishWithError(ctx, prepared, endMessage(endState));
            }
        }
        catch (RuntimeUnavailableException e) {
            log.warn("Direct runtime unavailable, sessionId={}, state={}, retryable={}", ctx.sessionId, e.state(), e.retryable());
            if (!terminal) finishWithError(ctx, prepared, e.getMessage());
        }
        catch (Exception e) {
            // The runtime may still be executing; the outcome is unknown and is never re-sent automatically.
            log.warn("Direct turn stream failed, sessionId={}, traceId={}, errorType={}", ctx.sessionId, ctx.traceId,
                e.getClass().getSimpleName());
            if (!terminal) finishWithError(ctx, prepared, "与运行环境的连接中断，本轮结果未知，请勿重复发送");
        }
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
