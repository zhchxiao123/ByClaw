package com.iwhalecloud.byai.gateway.direct;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/** Periodically projects runtime outbox events the platform has not acknowledged yet. */
@Slf4j
public class DirectProjectionReconciler {

    /** How often sub-agent and team events of page-streamed sessions are projected. */
    static final long HELD_SCOPE_INTERVAL_MILLIS = 1000L;

    private final DirectChatGateway gateway;

    /**
     * Its own thread: the shared scheduler also runs sweeps that wait out unreachable runtimes, and
     * sub-agents must appear within about a second regardless.
     */
    private final ScheduledExecutorService heldScopes = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "direct-held-scope");
        thread.setDaemon(true);
        return thread;
    });

    public DirectProjectionReconciler(DirectChatGateway gateway) {
        this.gateway = gateway;
        heldScopes.scheduleWithFixedDelay(this::projectHeldSessionScopes, HELD_SCOPE_INTERVAL_MILLIS,
            HELD_SCOPE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Scheduled(initialDelayString = "${byclaw.direct.reconcile-initial-delay-ms:15000}",
        fixedDelayString = "${byclaw.direct.reconcile-interval-ms:10000}")
    public void reconcile() {
        int projected = gateway.reconcileOnce();
        if (projected > 0) log.info("Direct projection reconciled {} runtime events", projected);
    }

    void projectHeldSessionScopes() {
        try {
            gateway.projectHeldSessionScopes();
        }
        catch (RuntimeException e) {
            log.warn("Direct held-session projection failed, errorType={}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() {
        heldScopes.shutdownNow();
    }
}
