package com.iwhalecloud.byai.gateway.direct;

import java.io.IOException;
import java.io.OutputStream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.state.infrastructure.utils.CompletionsUtils;
import com.iwhalecloud.byai.gateway.route.A2aRouteService;
import com.iwhalecloud.byai.gateway.route.InterfaceRouteService;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.common.enums.AgentTypeEnum;
import com.iwhalecloud.byai.state.domain.chat.model.MessageContext;
import com.iwhalecloud.byai.state.domain.chat.service.ChatProcessContext;
import com.iwhalecloud.byai.state.domain.chat.service.ParamService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Third-party employees for a Direct runtime. Such an employee is an external agent the platform
 * integrates over an HTTP interface or A2A, with its endpoint, credentials and network access held
 * here; a Direct turn addressed to one runs on the owner's runtime, which asks this endpoint to
 * call the agent and streams the answer back as its own. Only the owner of the Direct session may
 * relay, and only to a third-party employee the platform resolves for them.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectThirdPartyRelayController {

    private final DirectRouteSelector selector;
    private final ParamService params;
    private final InterfaceRouteService interfaces;
    private final A2aRouteService a2a;

    public DirectThirdPartyRelayController(DirectRouteSelector selector, ParamService params,
                                           InterfaceRouteService interfaces, A2aRouteService a2a) {
        this.selector = selector;
        this.params = params;
        this.interfaces = interfaces;
        this.a2a = a2a;
    }

    /** The turn the runtime relays: its Direct session, the employee and the user's text. */
    public record RelayRequest(Long sessionId, Long agentId, String chatContent) {
    }

    @PostMapping(value = "/direct/third-party/relay", produces = "text/event-stream")
    public void relay(@RequestBody RelayRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        OutputStream out = response.getOutputStream();
        var login = CurrentUserHolder.getLoginInfo();
        if (login == null || login.getUserId() == null || request == null || request.sessionId() == null
            || request.agentId() == null || selector.requireOwnerIfDirect(request.sessionId(), login) == null) {
            refuse(out, "第三方员工中继仅限本人的 Direct 会话");
            return;
        }
        AssistantChatDto dto = new AssistantChatDto();
        dto.setSessionId(request.sessionId());
        dto.setAgentId(request.agentId());
        dto.setChatContent(request.chatContent() == null ? "" : request.chatContent());
        ChatProcessContext ctx = new ChatProcessContext(out, dto);
        ctx.sessionId = request.sessionId();
        ctx.userId = login.getUserId();
        ctx.messageContext = new MessageContext(AgentTypeEnum.AGENT, 0L);
        ctx.params = params.getParams(ctx);
        String integration = DirectRouteSelector.thirdPartyIntegration(ctx);
        if (integration == null) {
            refuse(out, "该员工不是第三方员工");
            return;
        }
        log.info("Direct third-party relay, sessionId={}, agentId={}, integration={}", request.sessionId(),
            request.agentId(), integration);
        if ("A2A".equals(integration)) a2a.route(ctx);
        else interfaces.route(ctx);
        out.flush();
    }

    private static void refuse(OutputStream out, String message) throws IOException {
        com.alibaba.fastjson.JSONObject error = new com.alibaba.fastjson.JSONObject();
        error.put("message", message);
        CompletionsUtils.responseWrite(out, com.iwhalecloud.byai.state.infrastructure.common.constants.SseResponseEventEnum.error,
            error.toJSONString());
        out.flush();
    }
}
