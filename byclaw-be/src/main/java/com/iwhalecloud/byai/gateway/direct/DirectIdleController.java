package com.iwhalecloud.byai.gateway.direct;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;

/**
 * Drains and pauses runtimes that have been idle long enough, and suspends the runtimes of owner
 * scopes whose access was revoked after stopping their work. Never deletes an instance.
 */
@Slf4j
public class DirectIdleController {

    private final RuntimeBindingService bindings;
    private final Supplier<List<String>> readyOwners;
    private RuntimeScopeAuthority authority = RuntimeScopeAuthority.ALWAYS;
    private Function<String, List<String>> stopOwnerWork = owner -> List.of();

    public DirectIdleController(RuntimeBindingService bindings, Supplier<List<String>> readyOwners) {
        this.bindings = bindings;
        this.readyOwners = readyOwners;
    }

    private java.util.function.Consumer<String> refreshGrants = owner -> { };

    /** Keep each running runtime's enterprise-scoped grants current, so revocations reach it. */
    public DirectIdleController withGrantRefresh(java.util.function.Consumer<String> refreshGrants) {
        this.refreshGrants = refreshGrants;
        return this;
    }

    public DirectIdleController withRevocation(RuntimeScopeAuthority authority, Function<String, List<String>> stopOwnerWork) {
        this.authority = authority;
        this.stopOwnerWork = stopOwnerWork;
        return this;
    }

    @Scheduled(initialDelayString = "${byclaw.direct.idle-initial-delay-ms:30000}",
        fixedDelayString = "${byclaw.direct.idle-check-interval-ms:30000}")
    public void pauseIdleRuntimes() {
        for (String owner : readyOwners.get()) {
            try {
                String revoked = authority.revoked(owner);
                if (revoked != null) {
                    List<String> stopped = stopOwnerWork.apply(owner);
                    log.warn("Direct owner scope revoked, owner={}, reason={}, stopped={}", owner, revoked, stopped);
                    bindings.suspend(owner, revoked);
                    continue;
                }
                refreshGrants.accept(owner);
                bindings.pauseIfIdle(owner);
            }
            catch (RuntimeException e) {
                log.warn("Direct idle check failed, owner={}, errorType={}", owner, e.getClass().getSimpleName());
            }
        }
    }
}
