package com.iwhalecloud.byai.gateway.direct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/**
 * Direct status by layer. Owners read their own runtime (optionally one of their requests);
 * operators read any owner scope. Reading never wakes a runtime.
 */
@RestController
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectStatusController {

    private final DirectStatus status;
    private final RuntimeBindingService bindings;

    public DirectStatusController(DirectStatus status, RuntimeBindingService bindings) {
        this.status = status;
        this.bindings = bindings;
    }

    @GetMapping("/direct/status")
    public ResponseUtil<Object> mine(@RequestParam(value = "sessionId", required = false) Long sessionId,
                                     @RequestParam(value = "requestId", required = false) String requestId) {
        var login = CurrentUserHolder.getLoginInfo();
        if (login == null || login.getUserId() == null) return ResponseUtil.failRes("login required");
        return ResponseUtil.successRes(status.of(DirectRouteSelector.owner(login).key(), requestId, sessionId, false));
    }

    public record OwnerRequest(String ownerScope) {
    }

    /** Replace an instance that cannot be resumed (or stopped) with a new one over the same owner state. */
    @org.springframework.web.bind.annotation.PostMapping("/direct/ops/runtime/recreate")
    public ResponseUtil<Object> recreate(@org.springframework.web.bind.annotation.RequestBody OwnerRequest request) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (request == null || request.ownerScope() == null || !request.ownerScope().matches("^u\\d+\\.e\\d+$")) {
            return ResponseUtil.failRes("ownerScope is required");
        }
        try {
            var binding = bindings.recreateInstance(request.ownerScope());
            return ResponseUtil.successRes(java.util.Map.of("state", binding.getState(), "generation", binding.getGeneration()));
        }
        catch (IllegalStateException e) {
            return ResponseUtil.failRes(e.getMessage());
        }
    }

    @GetMapping("/direct/ops/status")
    public ResponseUtil<Object> owner(@RequestParam("ownerScope") String ownerScope,
                                      @RequestParam(value = "sessionId", required = false) Long sessionId,
                                      @RequestParam(value = "requestId", required = false) String requestId) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (ownerScope == null || !ownerScope.matches("^u\\d+\\.e\\d+$")) return ResponseUtil.failRes("ownerScope is required");
        return ResponseUtil.successRes(status.of(ownerScope, requestId, sessionId, true));
    }
}
