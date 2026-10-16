package com.iwhalecloud.byai.gateway.direct;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson.JSON;
import com.iwhalecloud.byai.gateway.direct.mapper.RequestAdmissionMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;

/** Primary-key and compare-and-set semantics of RequestAdmissionMapper over a map. */
final class InMemoryRequestAdmissions implements RequestAdmissionMapper {

    final Map<String, RequestAdmission> rows = new ConcurrentHashMap<>();

    private static String key(long sessionId, String requestId) {
        return sessionId + "\u0000" + requestId;
    }

    @Override
    public synchronized RequestAdmission select(long sessionId, String requestId) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        return row == null ? null : JSON.parseObject(JSON.toJSONString(row), RequestAdmission.class);
    }

    @Override
    public synchronized RequestAdmission selectByAnswer(long sessionId, long answerMessageId) {
        return rows.values().stream().filter(row -> row.getSessionId() == sessionId
                && Long.valueOf(answerMessageId).equals(row.getAnswerMessageId()))
            .findFirst().map(row -> JSON.parseObject(JSON.toJSONString(row), RequestAdmission.class)).orElse(null);
    }

    @Override
    public synchronized int insert(RequestAdmission admission) {
        String key = key(admission.getSessionId(), admission.getRequestId());
        if (rows.containsKey(key)) throw new org.springframework.dao.DuplicateKeyException("pk");
        RequestAdmission row = JSON.parseObject(JSON.toJSONString(admission), RequestAdmission.class);
        row.setUpdateTime(new Date());
        rows.put(key, row);
        return 1;
    }

    @Override
    public synchronized int bind(long sessionId, String requestId, String expectedState, String nextState,
                                 Long userMessageId, Long answerMessageId, String traceId) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        if (row == null || !row.getState().equals(expectedState)) return 0;
        row.setUserMessageId(userMessageId);
        row.setAnswerMessageId(answerMessageId);
        row.setTraceId(traceId);
        row.setState(nextState);
        return 1;
    }

    @Override
    public synchronized int record(long sessionId, String requestId, String state, String detail) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        if (row == null) return 0;
        row.setState(state);
        row.setDetail(detail);
        return 1;
    }

    @Override
    public synchronized int transition(long sessionId, String requestId, String expectedState, String nextState,
                                       String detail) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        if (row == null || !row.getState().equals(expectedState)) return 0;
        row.setState(nextState);
        row.setDetail(detail);
        return 1;
    }

    @Override
    public synchronized RequestAdmission selectFirstInState(long sessionId, String state) {
        return rows.values().stream().filter(row -> row.getSessionId() == sessionId && state.equals(row.getState()))
            .min(java.util.Comparator.comparing(RequestAdmission::getRequestId))
            .map(row -> JSON.parseObject(JSON.toJSONString(row), RequestAdmission.class)).orElse(null);
    }

    String state(long sessionId, String requestId) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        return row == null ? null : row.getState();
    }

    String detail(long sessionId, String requestId) {
        RequestAdmission row = rows.get(key(sessionId, requestId));
        return row == null ? null : row.getDetail();
    }
}
