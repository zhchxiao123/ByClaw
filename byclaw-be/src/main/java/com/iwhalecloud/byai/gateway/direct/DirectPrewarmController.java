package com.iwhalecloud.byai.gateway.direct;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;
import com.iwhalecloud.byai.manager.domain.resource.service.SsResourceService;
import com.iwhalecloud.byai.manager.entity.resource.SsResource;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Starts (or wakes) the owner's runtime while the user is still writing, so the turn they are
 * about to send finds it ready instead of waiting for a cold start. Only when that turn would
 * run on Direct; the answer never waits for the runtime.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectPrewarmController {

    private final DirectRouteSelector selector;
    private final RuntimeBindingService bindings;
    private final SsResourceService resources;
    private final ExecutorService executor;

    public DirectPrewarmController(DirectRouteSelector selector, RuntimeBindingService bindings,
                                   SsResourceService resources,
                                   @Qualifier("directTurnExecutor") ExecutorService directTurnExecutor) {
        this.selector = selector;
        this.bindings = bindings;
        this.resources = resources;
        this.executor = directTurnExecutor;
    }

    /** The conversation the user is writing in: an existing session, or the assistant of a new one. */
    public record PrewarmRequest(Long sessionId, Long agentId, Long projectId) {
    }

    @PostMapping("/direct/runtime/prewarm")
    public ResponseUtil<Object> prewarm(@RequestBody(required = false) PrewarmRequest request) {
        var login = CurrentUserHolder.getLoginInfo();
        if (login == null || login.getUserId() == null) return ResponseUtil.failRes("login required");
        PrewarmRequest conversation = request == null ? new PrewarmRequest(null, null, null) : request;
        SsResource agent = conversation.agentId() == null ? null : resources.findById(conversation.agentId());
        // A third-party employee's turn keeps its own route; waking the runtime for one only costs an idle start.
        var owner = selector.ownerOfNextTurn(conversation.sessionId(), agent == null ? null : agent.getWorkerAgentType(),
            false, conversation.projectId(), login);
        if (owner.isEmpty()) return ResponseUtil.successRes(Map.of("prewarm", false));
        String state = bindings.find(owner.get()).map(RuntimeBinding::getState).orElse(null);
        if (!RuntimeBindingService.READY.equals(state)) {
            executor.execute(() -> {
                try {
                    bindings.ensure(owner.get());
                }
                catch (RuntimeException e) {
                    // The turn itself reports a runtime that cannot start; a prewarm only tried early.
                    log.warn("Direct runtime prewarm failed, owner={}, errorType={}", owner.get().key(),
                        e.getClass().getSimpleName());
                }
            });
        }
        return ResponseUtil.successRes(Map.of("prewarm", true, "state", state == null ? "none" : state));
    }
}
