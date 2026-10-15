package com.iwhalecloud.byai.gateway.direct;

import java.io.OutputStream;

import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.common.login.bean.LoginInfo;
import com.iwhalecloud.byai.gateway.direct.DirectAdmissions.Kind;
import com.iwhalecloud.byai.gateway.direct.DirectAdmissions.Reservation;
import com.iwhalecloud.byai.state.domain.chat.dto.AssistantChatDto;
import com.iwhalecloud.byai.state.infrastructure.common.constants.SseResponseEventEnum;
import com.iwhalecloud.byai.state.infrastructure.utils.CompletionsUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Admission gate for turns into an existing Direct session, run before any message is created.
 * A repeated business request reports its original admission; it never starts a second turn.
 */
@Slf4j
public class DirectTurnGate {

    private final DirectRouteSelector routes;
    private final DirectAdmissions admissions;

    public DirectTurnGate(DirectRouteSelector routes, DirectAdmissions admissions) {
        this.routes = routes;
        this.admissions = admissions;
    }

    /** @return false when the request was answered here and must not be executed. */
    public boolean admit(AssistantChatDto dto, OutputStream out, LoginInfo login) {
        if (dto == null || dto.getSessionId() == null) return true;
        RuntimeOwnerScope owner = routes.requireOwnerIfDirect(dto.getSessionId(), login);
        String requestId = DirectIntent.requestId(dto);
        if (owner == null || requestId == null) return true;
        Reservation reservation = admissions.reserve(owner, dto.getSessionId(), requestId, DirectIntent.digest(dto));
        if (reservation.kind() == Kind.NEW) {
            dto.setDirectReservedRequestId(requestId);
            return true;
        }
        String message = reservation.kind() == Kind.CONFLICT ? "请求编号已用于不同的内容，已拒绝"
            : DirectAdmissions.describe(reservation.admission());
        log.info("Direct request not executed again, sessionId={}, kind={}, state={}", dto.getSessionId(),
            reservation.kind(), reservation.admission() == null ? null : reservation.admission().getState());
        JSONObject payload = new JSONObject(true);
        payload.put("message", message);
        payload.put("traceback", message);
        payload.put("sessionId", String.valueOf(dto.getSessionId()));
        payload.put("requestId", requestId);
        payload.put("admissionState", reservation.kind() == Kind.CONFLICT ? "conflict"
            : reservation.admission() == null ? null : reservation.admission().getState());
        CompletionsUtils.responseWrite(out, SseResponseEventEnum.error, payload.toJSONString(), dto.getSessionId());
        return false;
    }
}
