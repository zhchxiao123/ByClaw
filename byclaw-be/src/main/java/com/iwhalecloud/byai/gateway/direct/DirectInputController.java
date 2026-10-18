package com.iwhalecloud.byai.gateway.direct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.common.message.service.ByaiMessageHotService;
import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;
import com.iwhalecloud.byai.state.domain.chat.service.TraceIdCodec;

/**
 * Input while a Direct session answers, as in DSH: steer words into the running answer, or take
 * back a message still waiting behind it (its question and answer leave the conversation).
 */
@RestController
@RequestMapping("/direct/input")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectInputController {

    private final DirectRouteSelector routes;
    private final DirectChatGateway gateway;
    private final DirectAdmissions admissions;
    private final ByaiMessageHotService messages;

    public DirectInputController(DirectRouteSelector routes, DirectChatGateway gateway, DirectAdmissions admissions,
                                 ByaiMessageHotService messages) {
        this.routes = routes;
        this.gateway = gateway;
        this.admissions = admissions;
        this.messages = messages;
    }

    public record Steer(Long sessionId, String text) {
    }

    public record Withdraw(Long sessionId, Long answerMessageId) {
    }

    @PostMapping("/steer")
    public ResponseUtil<JSONObject> steer(@RequestBody Steer steer) {
        if (steer == null || steer.sessionId() == null || steer.text() == null || steer.text().isBlank()) {
            return ResponseUtil.failRes("sessionId and text are required");
        }
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(steer.sessionId(), CurrentUserHolder.getLoginInfo());
        if (owner == null) return ResponseUtil.failRes("not a Direct session");
        return ResponseUtil.successRes(gateway.steer(owner, steer.sessionId(), steer.text()));
    }

    @PostMapping("/withdraw")
    public ResponseUtil<JSONObject> withdraw(@RequestBody Withdraw withdraw) {
        if (withdraw == null || withdraw.sessionId() == null || withdraw.answerMessageId() == null) {
            return ResponseUtil.failRes("sessionId and answerMessageId are required");
        }
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(withdraw.sessionId(), CurrentUserHolder.getLoginInfo());
        if (owner == null) return ResponseUtil.failRes("not a Direct session");
        var admission = admissions.findByAnswer(withdraw.sessionId(), withdraw.answerMessageId());
        JSONObject result = gateway.withdraw(owner, withdraw.sessionId(), withdraw.answerMessageId());
        if ("withdrawn".equals(result.getString("result")) && admission != null
            && TraceIdCodec.canDecode(admission.getTraceId())) {
            // Its turn still ends later (closing the platform's bookkeeping); the deleted messages stay deleted.
            var ids = TraceIdCodec.decode(admission.getTraceId());
            messages.deleteById(ids.getUserMessageId());
            messages.deleteById(ids.getModelAnswerMessageId());
        }
        return ResponseUtil.successRes(result);
    }
}
