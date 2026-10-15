package com.iwhalecloud.byai.gateway.direct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/** Background jobs of the caller's own Direct session: list, inspect, stop. */
@RestController
@RequestMapping("/direct/jobs")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectJobController {

    private final DirectRouteSelector routes;
    private final DirectChatGateway gateway;

    public DirectJobController(DirectRouteSelector routes, DirectChatGateway gateway) {
        this.routes = routes;
        this.gateway = gateway;
    }

    public record JobQuery(Long sessionId, String jobId) {
    }

    @PostMapping("/list")
    public ResponseUtil<JSONObject> list(@RequestBody JobQuery query) {
        return call(query, false, false);
    }

    @PostMapping("/get")
    public ResponseUtil<JSONObject> get(@RequestBody JobQuery query) {
        return call(query, true, false);
    }

    @PostMapping("/kill")
    public ResponseUtil<JSONObject> kill(@RequestBody JobQuery query) {
        return call(query, true, true);
    }

    private ResponseUtil<JSONObject> call(JobQuery query, boolean needsJob, boolean kill) {
        if (query == null || query.sessionId() == null || (needsJob && query.jobId() == null)) {
            return ResponseUtil.failRes("sessionId" + (needsJob ? " and jobId" : "") + " are required");
        }
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(query.sessionId(), CurrentUserHolder.getLoginInfo());
        if (owner == null) return ResponseUtil.failRes("not a Direct session");
        return ResponseUtil.successRes(gateway.jobs(owner, query.sessionId(), needsJob ? query.jobId() : null, kill));
    }
}
