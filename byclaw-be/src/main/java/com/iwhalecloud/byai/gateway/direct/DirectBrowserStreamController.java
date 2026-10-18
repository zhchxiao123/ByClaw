package com.iwhalecloud.byai.gateway.direct;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.RuntimeBindingService.ReadyRuntime;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/**
 * A page that reads its Direct turn from the runtime itself: the proxy asks here whether the watch
 * ticket it forwards is for the caller's own turn, and the page reports the end of its stream so
 * the platform persists the turn at once.
 */
@RestController
@RequestMapping("/direct/stream")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectBrowserStreamController {

    static final String HOST_HEADER = "X-Direct-Runtime-Host";
    static final String TOKEN_HEADER = "X-Direct-Traffic-Token";

    private final DirectRouteSelector routes;
    private final DirectChatGateway gateway;
    private final DirectRuntimeProperties properties;

    public DirectBrowserStreamController(DirectRouteSelector routes, DirectChatGateway gateway,
                                         DirectRuntimeProperties properties) {
        this.routes = routes;
        this.gateway = gateway;
        this.properties = properties;
    }

    /** Proxy subrequest: 204 with the runtime host and traffic token, or 403. Never seen by the page. */
    @GetMapping("/authorize")
    public void authorize(HttpServletRequest request, HttpServletResponse response) {
        LoginInfo login = CurrentUserHolder.getLoginInfo();
        Optional<ReadyRuntime> runtime = properties.isBrowserStream()
            ? gateway.authorizeBrowserTurn(request.getHeader(DirectProtocol.WATCH_HEADER),
                request.getHeader(DirectProtocol.SIGNATURE_HEADER), sessionId -> routes.requireOwnerIfDirect(sessionId, login))
            : Optional.empty();
        if (runtime.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setHeader(HOST_HEADER, properties.getRuntime().getPort() + "-" + runtime.get().sandboxId() + "."
            + properties.getCube().getSandboxDomain());
        response.setHeader(TOKEN_HEADER, runtime.get().trafficToken());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    /** A session, and the trace of one page-streamed turn in it (absent: all of them, or the latest). */
    public record Settle(Long sessionId, String traceId) {
    }

    /** A reloaded page asks to read its session's page-streamed turn again; no data when there is none. */
    @PostMapping("/rewatch")
    public ResponseUtil<JSONObject> rewatch(@RequestBody Settle rewatch) {
        if (rewatch == null || rewatch.sessionId() == null) return ResponseUtil.failRes("sessionId is required");
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(rewatch.sessionId(), CurrentUserHolder.getLoginInfo());
        if (owner == null || !properties.isBrowserStream()) return ResponseUtil.successRes(null);
        return ResponseUtil.successRes(gateway.rewatchBrowserTurn(owner, rewatch.sessionId(), rewatch.traceId()).orElse(null));
    }

    /** The page's stream ended: persist the session's runtime output now. */
    @PostMapping("/settle")
    public ResponseUtil<JSONObject> settle(@RequestBody Settle settle) {
        if (settle == null || settle.sessionId() == null) return ResponseUtil.failRes("sessionId is required");
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(settle.sessionId(), CurrentUserHolder.getLoginInfo());
        if (owner == null) return ResponseUtil.failRes("not a Direct session");
        JSONObject result = new JSONObject();
        result.put("projected", gateway.settleBrowserTurn(owner, settle.sessionId(), settle.traceId()));
        return ResponseUtil.successRes(result);
    }
}
