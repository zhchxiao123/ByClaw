package com.iwhalecloud.byai.gateway.direct;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;
import com.iwhalecloud.byai.manager.interfaces.response.ResponseUtil;

/**
 * Operator entry for backing up and restoring one owner's Direct runtime state. Platform
 * administrators and operators only; an owner never restores (or reads) another owner's state.
 */
@RestController
@RequestMapping("/direct/ops/backups")
@ConditionalOnProperty(prefix = "byclaw.direct", name = "enabled", havingValue = "true")
public class DirectBackupController {

    private final DirectBackups backups;

    public DirectBackupController(DirectBackups backups) {
        this.backups = backups;
    }

    public record BackupRequest(String ownerScope) {
    }

    public record RestoreRequest(String ownerScope, String backupId) {
    }

    @PostMapping
    public ResponseUtil<Object> backup(@RequestBody BackupRequest request) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (request == null || !validOwner(request.ownerScope())) return ResponseUtil.failRes("ownerScope is required");
        return run(() -> backups.backup(request.ownerScope()));
    }

    @GetMapping
    public ResponseUtil<Object> list(@RequestParam("ownerScope") String ownerScope) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (!validOwner(ownerScope)) return ResponseUtil.failRes("ownerScope is required");
        return run(() -> backups.list(ownerScope));
    }

    @PostMapping("/restore")
    public ResponseUtil<Object> restore(@RequestBody RestoreRequest request) {
        if (!CurrentUserHolder.isPlatformAdminOrOperator()) return ResponseUtil.failRes("forbidden");
        if (request == null || !validOwner(request.ownerScope())) return ResponseUtil.failRes("ownerScope is required");
        return run(() -> backups.restore(request.ownerScope(), request.backupId()));
    }

    private static boolean validOwner(String ownerScope) {
        return ownerScope != null && ownerScope.matches("^u\\d+\\.e\\d+$");
    }

    private static ResponseUtil<Object> run(java.util.function.Supplier<Object> work) {
        try {
            return ResponseUtil.successRes(work.get());
        }
        catch (IllegalStateException | IllegalArgumentException e) {
            // The reason code (runtime_running, archive_corrupt, identity_mismatch, ...) names what refused it.
            return ResponseUtil.failRes(e.getMessage());
        }
    }
}
