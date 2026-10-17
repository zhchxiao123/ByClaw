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
    private final ExecutorService executor;

    public DirectPrewarmController(DirectRouteSelector selector, RuntimeBindingService bindings,
                                   @Qualifier("directTurnExecutor") ExecutorService directTurnExecutor) {
        this.selector = selector;
        this.bindings = bindings;
        this.executor = directTurnExecutor;
    }

    /**
     * The conversation the user is writing in: an existing session, or a new one (in a project or
     * not). Every assistant of a new session runs on Direct, so the agent does not narrow it.
     */
    public record PrewarmRequest(Long sessionId, Long agentId, Long projectId) {
    }

    @PostMapping("/direct/runtime/prewarm")
    public ResponseUtil<Object> prewarm(@RequestBody(required = false) PrewarmRequest request) {
        var login = CurrentUserHolder.getLoginInfo();
        if (login == null || login.getUserId() == null) return ResponseUtil.failRes("login required");
        PrewarmRequest conversation = request == null ? new PrewarmRequest(null, null, null) : request;
        var owner = selector.ownerOfNextTurn(conversation.sessionId(), conversation.projectId(), login);
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
