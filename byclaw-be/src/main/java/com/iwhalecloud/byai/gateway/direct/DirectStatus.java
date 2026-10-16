package com.iwhalecloud.byai.gateway.direct;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.iwhalecloud.byai.gateway.direct.mapper.ProjectionProgressMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;
import com.iwhalecloud.byai.gateway.direct.persistence.RuntimeBinding;

/**
 * One owner's Direct status, layer by layer, without waking anything: the environment (binding
 * state, fault, generation, template), the runtime process when it already runs (identity,
 * leases, turns, pending projection), the execution queue (open admissions), the sync watermark
 * per session and recent failures. Every value carries its time. Tokens and secrets never appear;
 * owners see a hashed sandbox reference and the fault's user message, operators the raw values.
 */
public class DirectStatus {

    private final RuntimeBindingService bindings;
    private final DirectChatGateway gateway;
    private final ProjectionProgressMapper progress;
    private final DirectTransactions transactions;
    private final Clock clock;

    public DirectStatus(RuntimeBindingService bindings, DirectChatGateway gateway, ProjectionProgressMapper progress,
                        DirectTransactions transactions, Clock clock) {
        this.bindings = bindings;
        this.gateway = gateway;
        this.progress = progress;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The page's sandbox status for a binding state: ready is running; being created, resumed or
     * paused is transitioning; anything else is not running (with its reason shown separately).
     */
    public static String pageStatus(String state) {
        if (state == null) return "NONE";
        return switch (state) {
            case RuntimeBindingService.READY -> "RUNNING";
            case RuntimeBindingService.PROVISIONING, RuntimeBindingService.STARTING, RuntimeBindingService.RESUMING -> "STARTING";
            case RuntimeBindingService.DRAINING -> "RELEASING";
            case RuntimeBindingService.PAUSED -> "PAUSED";
            case RuntimeBindingService.RESTORE_PENDING -> "RESTORE_PENDING";
            default -> "FAILED";
        };
    }

    /** The fault named by a binding's last error ({@code code: detail}), if any. */
    public static Optional<RuntimeFault> fault(String lastError) {
        if (StringUtils.isBlank(lastError)) return Optional.empty();
        String code = StringUtils.substringBefore(lastError, ":").trim();
        for (RuntimeFault fault : RuntimeFault.values()) {
            if (fault.code().equals(code)) return Optional.of(fault);
        }
        return Optional.empty();
    }

    public JSONObject of(String ownerKey, String requestId, Long sessionId, boolean operator) {
        JSONObject view = new JSONObject(true);
        long now = clock.millis();
        view.put("observedAt", now);
        view.put("ownerScope", ownerKey);
        RuntimeBinding binding = bindings.find(ownerKey).orElse(null);
        view.put("environment", environment(binding, operator));
        view.put("runtime", runtime(ownerKey, binding, now));
        List<RequestAdmission> open = transactions.requiresNew(() -> progress.openAdmissions(0, 5000)).stream()
            .filter(admission -> ownerKey.equals(admission.getOwnerScope())).toList();
        view.put("queue", queue(open, now));
        view.put("sync", sync(ownerKey, (JSONObject) view.getJSONObject("runtime").get("pendingProjection")));
        List<JSONObject> failures = new ArrayList<>();
        for (RequestAdmission failed : transactions.requiresNew(() -> progress.recentFailures(ownerKey, 10))) {
            failures.add(admissionView(failed, now));
        }
        view.put("recentFailures", failures);
        if (requestId != null && sessionId != null) view.put("request", request(ownerKey, sessionId, requestId, now));
        return view;
    }

    private static JSONObject environment(RuntimeBinding binding, boolean operator) {
        JSONObject environment = new JSONObject(true);
        if (binding == null) {
            environment.put("state", "none");
            environment.put("pageStatus", "NONE");
            return environment;
        }
        environment.put("state", binding.getState());
        environment.put("pageStatus", pageStatus(binding.getState()));
        environment.put("bindingId", binding.getBindingId());
        environment.put("generation", binding.getGeneration());
        environment.put("provider", binding.getProvider());
        environment.put("templateId", binding.getTemplateId());
        environment.put("protocol", DirectProtocol.PROTOCOL);
        environment.put("sandbox", binding.getSandboxId() == null ? null
            : operator ? binding.getSandboxId() : "sbx-" + DirectProtocol.sha256Hex(binding.getSandboxId()
                .getBytes(StandardCharsets.UTF_8)).substring(0, 12));
        environment.put("since", time(binding.getUpdateTime()));
        environment.put("createdAt", time(binding.getCreateTime()));
        fault(binding.getLastError()).ifPresent(fault -> {
            JSONObject value = new JSONObject(true);
            value.put("code", fault.code());
            value.put("message", fault.message());
            value.put("retryable", fault.retryable());
            if (operator) value.put("detail", binding.getLastError());
            environment.put("fault", value);
        });
        if (operator && environment.get("fault") == null && binding.getLastError() != null) {
            environment.put("lastError", binding.getLastError());
        }
        return environment;
    }

    private JSONObject runtime(String ownerKey, RuntimeBinding binding, long now) {
        JSONObject runtime = new JSONObject(true);
        Optional<JSONObject> status = binding == null || !RuntimeBindingService.READY.equals(binding.getState())
            ? Optional.empty() : gateway.runtimeStatus(ownerKey);
        runtime.put("running", status.isPresent());
        if (status.isEmpty()) {
            runtime.put("note", binding == null ? "no runtime" : "not queried: the runtime is " + binding.getState()
                + " and a status read never wakes it");
            return runtime;
        }
        JSONObject value = status.get();
        runtime.put("bootId", value.getString("bootId"));
        runtime.put("startedAt", value.get("startedAt"));
        runtime.put("lineage", value.getString("lineage"));
        runtime.put("generation", value.get("generation"));
        runtime.put("protocol", value.getString("protocol"));
        JSONObject leases = value.getJSONObject("leases");
        runtime.put("leases", leases == null ? null : leases.get("kinds"));
        runtime.put("lastReleasedAt", leases == null ? null : leases.get("lastReleasedAt"));
        runtime.put("activeTurns", value.get("running"));
        runtime.put("draining", value.get("draining"));
        runtime.put("pendingProjection", value.getJSONObject("pendingProjection"));
        runtime.put("clockSkewMs", value.containsKey("now") ? value.getLongValue("now") - now : null);
        return runtime;
    }

    private static JSONObject queue(List<RequestAdmission> open, long now) {
        JSONObject queue = new JSONObject(true);
        Map<String, Integer> byState = new TreeMap<>();
        List<JSONObject> items = new ArrayList<>();
        for (RequestAdmission admission : open) {
            byState.merge(admission.getState(), 1, Integer::sum);
            if (items.size() < 20) items.add(admissionView(admission, now));
        }
        queue.put("open", open.size());
        queue.put("byState", byState);
        queue.put("items", items);
        return queue;
    }

    private List<JSONObject> sync(String ownerKey, JSONObject pending) {
        List<JSONObject> sessions = new ArrayList<>();
        for (Map<String, Object> row : transactions.requiresNew(() -> progress.progressOf(ownerKey))) {
            JSONObject value = (JSONObject) JSON.toJSON(row);
            JSONObject session = new JSONObject(true);
            String sessionId = value.getString("sessionId");
            session.put("sessionId", sessionId);
            session.put("projectedThroughSeq", value.getLongValue("throughSeq"));
            session.put("lineage", value.getString("lineage"));
            // Unacknowledged runtime events are the projection lag; unknown while the runtime sleeps.
            session.put("runtimePending", pending == null ? null : pending.getLongValue(sessionId));
            sessions.add(session);
        }
        return sessions;
    }

    private JSONObject request(String ownerKey, long sessionId, String requestId, long now) {
        RequestAdmission admission = gateway.admission(sessionId, requestId);
        if (admission == null || !ownerKey.equals(admission.getOwnerScope())) return null;
        JSONObject view = admissionView(admission, now);
        view.put("userMessageId", admission.getUserMessageId());
        view.put("answerMessageId", admission.getAnswerMessageId());
        view.put("traceId", admission.getTraceId());
        view.put("projectedThroughSeq", gateway.projectedThroughSeq(sessionId));
        if (DirectAdmissions.OPEN.contains(admission.getState())) {
            gateway.runtimeTurnStateOf(ownerKey, admission).ifPresent(state -> view.put("runtimeState", state));
        }
        return view;
    }

    private static JSONObject admissionView(RequestAdmission admission, long now) {
        JSONObject view = new JSONObject(true);
        view.put("requestId", admission.getRequestId());
        view.put("sessionId", admission.getSessionId());
        view.put("state", admission.getState());
        view.put("detail", admission.getDetail());
        view.put("updatedAt", time(admission.getUpdateTime()));
        view.put("ageSeconds", admission.getUpdateTime() == null ? null : (now - admission.getUpdateTime().getTime()) / 1000);
        return view;
    }

    private static Long time(Date date) {
        return date == null ? null : date.getTime();
    }
}
