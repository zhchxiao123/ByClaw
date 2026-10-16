package com.iwhalecloud.byai.gateway.direct;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.gateway.direct.mapper.ProjectionProgressMapper;
import com.iwhalecloud.byai.gateway.direct.persistence.RequestAdmission;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/**
 * Operator switch for a Direct rollback: close admission first, wait until no admitted work is
 * open, then switch Direct off. Platform administrators and operators only.
 */
@RestController
@RequestMapping("/direct/ops/admission")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectRolloutController {

    private final DirectRollout rollout;
    private final ProjectionProgressMapper progress;
    private final DirectTransactions transactions;

    public DirectRolloutController(DirectRollout rollout, ProjectionProgressMapper progress, DirectTransactions transactions) {
        this.rollout = rollout;
        this.progress = progress;
        this.transactions = transactions;
    }

    public record AdmissionRequest(Boolean open) {
    }

    @GetMapping
    public ResponseUtil<Object> status() {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        return ResponseUtil.successRes(view());
    }

    @PostMapping
    public ResponseUtil<Object> set(@RequestBody AdmissionRequest request) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (request == null || request.open() == null) return ResponseUtil.failRes("open is required");
        rollout.setAdmission(request.open());
        return ResponseUtil.successRes(view());
    }

    /** Admission state and the admitted work still open (to be reconciled before Direct is switched off). */
    private Map<String, Object> view() {
        Map<String, Long> byState = new TreeMap<>();
        Map<String, Long> byOwner = new TreeMap<>();
        for (RequestAdmission open : transactions.requiresNew(() -> progress.openAdmissions(0, 5000))) {
            byState.merge(open.getState(), 1L, Long::sum);
            byOwner.merge(open.getOwnerScope(), 1L, Long::sum);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("admissionOpen", rollout.admissionOpen());
        view.put("openAdmissions", byState.values().stream().mapToLong(Long::longValue).sum());
        view.put("openByState", byState);
        view.put("openByOwner", byOwner);
        return view;
    }
}
