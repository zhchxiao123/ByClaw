package com.iwhalecloud.byai.gateway.direct;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import com.iwhalecloud.byai.gateway.direct.mapper.RequestAdmissionMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;

/**
 * Platform admission per (session, business requestId). A request is reserved before any message
 * is created; the same request with the same intent returns the original admission instead of a
 * new turn, and the same request id with a different intent is refused.
 */
public class DirectAdmissions {

    public static final String RESERVED = "reserved";
    public static final String DISPATCHING = "dispatching";
    public static final String ACCEPTED = "accepted";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String UNKNOWN = "unknown";
    public static final String REJECTED = "rejected";
    /** States in which the runtime may still have work; a query may refresh them from the runtime. */
    public static final Set<String> OPEN = Set.of(DISPATCHING, ACCEPTED, UNKNOWN);

    public enum Kind { NEW, DUPLICATE, CONFLICT }

    public record Reservation(Kind kind, RequestAdmission admission) {
    }

    private final RequestAdmissionMapper mapper;
    private final DirectTransactions transactions;

    public DirectAdmissions(RequestAdmissionMapper mapper) {
        this(mapper, DirectTransactions.DIRECT);
    }

    public DirectAdmissions(RequestAdmissionMapper mapper, DirectTransactions transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public Reservation reserve(RuntimeOwnerScope owner, long sessionId, String requestId, String intentDigest) {
        RequestAdmission existing = transactions.requiresNew(() -> mapper.select(sessionId, requestId));
        if (existing != null) return classify(owner, intentDigest, existing);
        RequestAdmission admission = new RequestAdmission();
        admission.setSessionId(sessionId);
        admission.setRequestId(requestId);
        admission.setOwnerScope(owner.key());
        admission.setIntentDigest(intentDigest);
        admission.setState(RESERVED);
        try {
            transactions.requiresNew(() -> mapper.insert(admission));
            return new Reservation(Kind.NEW, admission);
        }
        catch (DuplicateKeyException e) {
            // A concurrent attempt reserved first; its record is authoritative.
            return classify(owner, intentDigest, transactions.requiresNew(() -> mapper.select(sessionId, requestId)));
        }
    }

    private static Reservation classify(RuntimeOwnerScope owner, String intentDigest, RequestAdmission existing) {
        boolean same = existing != null && owner.key().equals(existing.getOwnerScope())
            && StringUtils.equals(intentDigest, existing.getIntentDigest());
        return new Reservation(same ? Kind.DUPLICATE : Kind.CONFLICT, existing);
    }

    public void bind(long sessionId, String requestId, Long userMessageId, Long answerMessageId, String traceId) {
        int updated = transactions.requiresNew(() ->
            mapper.bind(sessionId, requestId, RESERVED, DISPATCHING, userMessageId, answerMessageId, traceId));
        if (updated != 1) throw new IllegalStateException("Direct admission is not reserved for this turn");
    }

    public void record(long sessionId, String requestId, String state, String detail) {
        transactions.requiresNew(() -> mapper.record(sessionId, requestId, state, StringUtils.abbreviate(detail, 250)));
    }

    public RequestAdmission findByAnswer(long sessionId, long answerMessageId) {
        return transactions.requiresNew(() -> mapper.selectByAnswer(sessionId, answerMessageId));
    }

    public RequestAdmission find(long sessionId, String requestId) {
        return transactions.requiresNew(() -> mapper.select(sessionId, requestId));
    }

    /** User-facing description of an existing admission returned for a duplicate request. */
    public static String describe(RequestAdmission admission) {
        String state = admission == null ? UNKNOWN : admission.getState();
        return switch (state) {
            case COMPLETED -> "该请求已执行完成，请勿重复发送";
            case FAILED -> "该请求已执行失败，请修改后作为新请求发送";
            case CANCELLED -> "该请求已停止，请作为新请求重新发送";
            case REJECTED -> "该请求未被接纳，请作为新请求重新发送";
            case UNKNOWN -> "该请求执行结果未知，已阻止重复执行";
            case RESERVED, DISPATCHING -> "该请求尚未确认被运行环境接纳，可查询状态或作为新请求重新发送";
            default -> "该请求已接纳，正在处理，请勿重复发送";
        };
    }
}
