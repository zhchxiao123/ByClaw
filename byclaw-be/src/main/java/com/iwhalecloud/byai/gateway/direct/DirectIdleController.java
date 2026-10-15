package com.iwhalecloud.byai.gateway.direct;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;

/** Drains and pauses runtimes that have been idle long enough; never deletes an instance. */
@Slf4j
public class DirectIdleController {

    private final RuntimeBindingService bindings;
    private final Supplier<List<String>> readyOwners;

    public DirectIdleController(RuntimeBindingService bindings, Supplier<List<String>> readyOwners) {
        this.bindings = bindings;
        this.readyOwners = readyOwners;
    }

    @Scheduled(initialDelayString = "${byclaw.direct.idle-initial-delay-ms:30000}",
        fixedDelayString = "${byclaw.direct.idle-check-interval-ms:30000}")
    public void pauseIdleRuntimes() {
        for (String owner : readyOwners.get()) {
            try {
                bindings.pauseIfIdle(owner);
            }
            catch (RuntimeException e) {
                log.warn("Direct idle check failed, owner={}, errorType={}", owner, e.getClass().getSimpleName());
            }
        }
    }
}
