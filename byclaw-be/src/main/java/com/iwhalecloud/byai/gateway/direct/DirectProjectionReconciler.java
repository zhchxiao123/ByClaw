package com.iwhalecloud.byai.gateway.direct;

import org.springframework.scheduling.annotation.Scheduled;

import lombok.extern.slf4j.Slf4j;

/** Periodically projects runtime outbox events the platform has not acknowledged yet. */
@Slf4j
public class DirectProjectionReconciler {

    private final DirectChatGateway gateway;

    public DirectProjectionReconciler(DirectChatGateway gateway) {
        this.gateway = gateway;
    }

    @Scheduled(initialDelayString = "${byclaw.direct.reconcile-initial-delay-ms:15000}",
        fixedDelayString = "${byclaw.direct.reconcile-interval-ms:10000}")
    public void reconcile() {
        int projected = gateway.reconcileOnce();
        if (projected > 0) log.info("Direct projection reconciled {} runtime events", projected);
    }
}
