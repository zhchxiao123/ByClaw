package com.iwhalecloud.byai.gateway.direct;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/** Query the admission of a Direct request by its business requestId, e.g. after a lost response. */
@RestController
@RequestMapping("/direct/admission")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectAdmissionController {

    private final DirectRouteSelector routes;
    private final DirectAdmissions admissions;
    private final DirectChatGateway gateway;

    public DirectAdmissionController(DirectRouteSelector routes, DirectAdmissions admissions, DirectChatGateway gateway) {
        this.routes = routes;
        this.admissions = admissions;
        this.gateway = gateway;
    }

    public record StatusQuery(Long sessionId, String requestId) {
    }

    @PostMapping("/status")
    public ResponseUtil<Map<String, Object>> status(@RequestBody StatusQuery query) {
        if (query == null || query.sessionId() == null || query.requestId() == null) {
            return ResponseUtil.failRes("sessionId and requestId are required");
        }
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(query.sessionId(), CurrentUserHolder.getLoginInfo());
        RequestAdmission admission = owner == null ? null : admissions.find(query.sessionId(), query.requestId());
        if (admission == null) return ResponseUtil.failRes("request not found");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sessionId", admission.getSessionId());
        view.put("requestId", admission.getRequestId());
        view.put("state", admission.getState());
        view.put("userMessageId", admission.getUserMessageId());
        view.put("answerMessageId", admission.getAnswerMessageId());
        view.put("updatedAt", admission.getUpdateTime());
        // Open admissions are refreshed from a running runtime only; a query never wakes one.
        if (DirectAdmissions.OPEN.contains(admission.getState())) {
            gateway.runtimeTurnState(owner, admission).ifPresent(state -> view.put("runtimeState", state));
        }
        return ResponseUtil.successRes(view);
    }
}
