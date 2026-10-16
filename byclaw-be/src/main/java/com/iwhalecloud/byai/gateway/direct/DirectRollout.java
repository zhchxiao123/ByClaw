package com.iwhalecloud.byai.gateway.direct;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.mapper.RuntimeSessionRouteMapper;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.state.domain.chat.service.OutputStreamManager;
import com.iwhalecloud.byai.state.domain.chat.service.RunningOutputStreamRegistry;
import com.iwhalecloud.byai.state.domain.chat.service.StreamDispatchResult;
import com.iwhalecloud.byai.state.domain.chat.service.StreamRecordProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * Canary rollout and rollback of the Direct route, present whether or not Direct is enabled.
 * <ul>
 *   <li>Admission is shared by every backend through Redis: closing it stops new Direct sessions
 *   (they use Legacy) and new turns of existing Direct sessions, while work already admitted is
 *   still reconciled, stopped or answered.</li>
 *   <li>A session recorded as Direct never runs on Legacy, even with Direct switched off: its turn
 *   ends visibly instead, so two transports never write one session and Direct history is never
 *   continued as if it had been Legacy.</li>
 * </ul>
 */
@Slf4j
@Component
public class DirectRollout {

    static final String ADMISSION_KEY = "byclaw:direct:admission";
    static final String CLOSED = "closed";

    private final StringRedisTemplate redis;
    private final RuntimeSessionRouteMapper routes;
    private final ObjectProvider<StreamRecordProcessor> processor;
    private final ObjectProvider<OutputStreamManager> streams;
    private final ObjectProvider<RunningOutputStreamRegistry> running;
    private final Executor executor;
    private final AtomicLong streamClock = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public DirectRollout(StringRedisTemplate redis, RuntimeSessionRouteMapper routes,
                         ObjectProvider<StreamRecordProcessor> processor, ObjectProvider<OutputStreamManager> streams,
                         ObjectProvider<RunningOutputStreamRegistry> running) {
        this(redis, routes, processor, streams, running, command -> {
            Thread thread = new Thread(command, "direct-rollout");
            thread.setDaemon(true);
            thread.start();
        });
    }

    DirectRollout(StringRedisTemplate redis, RuntimeSessionRouteMapper routes, ObjectProvider<StreamRecordProcessor> processor,
                  ObjectProvider<OutputStreamManager> streams, ObjectProvider<RunningOutputStreamRegistry> running,
                  Executor executor) {
        this.redis = redis;
        this.routes = routes;
        this.processor = processor;
        this.streams = streams;
        this.running = running;
        this.executor = executor;
    }

    /** Open unless an operator closed it; an unreadable store keeps the last safe answer: closed. */
    public boolean admissionOpen() {
        try {
            return !CLOSED.equals(redis.opsForValue().get(ADMISSION_KEY));
        }
        catch (RuntimeException e) {
            log.warn("Direct admission state unreadable; treating admission as closed");
            return false;
        }
    }

    public void setAdmission(boolean open) {
        if (open) redis.delete(ADMISSION_KEY);
        else redis.opsForValue().set(ADMISSION_KEY, CLOSED);
        log.warn("Direct admission {}", open ? "opened" : "closed");
    }

    /** Whether the server recorded this session as Direct; never throws. */
    public boolean recordedDirect(Long sessionId) {
        try {
            return sessionId != null && DirectRouteSelector.DIRECT.equals(routes.selectBackend(sessionId));
        }
        catch (RuntimeException e) {
            // Unknown is not Legacy: refuse the turn rather than risk a second writer.
            log.warn("Direct session route unreadable, sessionId={}; refusing rather than routing to Legacy", sessionId);
            return sessionId != null;
        }
    }

    /**
     * End the turn on the page with a reason, without any runtime or Worker work. Like a Direct
     * turn, the context is registered first and the end is projected after the request returns.
     */
    public void endWithoutRuntime(ChatProcessContext ctx, String message) {
        ctx.directTransport = true;
        ctx.asyncResponse = true;
        String sessionKey = String.valueOf(ctx.sessionId);
        OutputStreamManager manager = streams.getIfAvailable();
        RunningOutputStreamRegistry registry = running.getIfAvailable();
        if (manager != null) {
            manager.withSessionLock(sessionKey, () -> {
                manager.putContext(sessionKey, ctx);
                if (registry != null) registry.markRunning(ctx);
                return null;
            });
        }
        executor.execute(() -> projectEnd(ctx, message));
    }

    private void projectEnd(ChatProcessContext ctx, String message) {
        JSONObject error = new JSONObject(true);
        error.put("trace_id", ctx.traceId);
        error.put("session_id", String.valueOf(ctx.sessionId));
        error.put("event_type", "error");
        error.put("source_agent_type", ctx.targetAgentType == null ? "" : ctx.targetAgentType);
        error.put("message_id", String.valueOf(ctx.modelAnswerMessageId));
        error.put("parent_message_id", "-1");
        error.put("timestamp", System.currentTimeMillis());
        error.put("data", new JSONObject());
        JSONObject metadata = new JSONObject();
        metadata.put("error", message);
        error.put("metadata", metadata);
        StreamRecordProcessor records = processor.getIfAvailable();
        if (records == null) return;
        long stamp = streamClock.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
        StreamDispatchResult result = records.processEvent(error, stamp + "-0", "direct");
        if (result.isTerminal()) records.afterAcknowledge(result);
    }
}
