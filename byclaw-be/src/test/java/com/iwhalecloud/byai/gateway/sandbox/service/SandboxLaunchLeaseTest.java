package com.iwhalecloud.byai.gateway.sandbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SandboxLaunchLeaseTest {
    private static final Duration FAST = Duration.ofMillis(10);

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Test
    void theLockIsRenewedWhileTheLaunchRunsAndNoLongerOnceClosed() throws Exception {
        AtomicInteger renewals = new AtomicInteger();
        try (var lease = SandboxLaunchLease.hold("lock", "token", 120, FAST, Duration.ofMinutes(1), (k, t, ttl) -> {
            assertThat(k).isEqualTo("lock");
            assertThat(t).isEqualTo("token");
            assertThat(ttl).isEqualTo(120);
            renewals.incrementAndGet();
            return true;
        })) {
            pause(120);
            assertThat(renewals.get()).isGreaterThanOrEqualTo(3);
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isTrue();
        }
        int afterClose = renewals.get();
        pause(80);
        assertThat(renewals.get()).isEqualTo(afterClose);
    }

    @Test
    void aLockTakenByAnotherOwnerIsReportedLostAndNotRenewedAgain() throws Exception {
        AtomicInteger renewals = new AtomicInteger();
        try (var lease = SandboxLaunchLease.hold("lock", "token", 120, FAST, Duration.ofMinutes(1),
            (k, t, ttl) -> renewals.incrementAndGet() < 3)) {
            pause(150);
            int stopped = renewals.get();
            assertThat(stopped).isEqualTo(3);
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isFalse();
            pause(50);
            assertThat(renewals.get()).isEqualTo(stopped);
        }
    }

    @Test
    void aRenewalErrorCountsAsLost() throws Exception {
        try (var lease = SandboxLaunchLease.hold("lock", "token", 120, FAST, Duration.ofMinutes(1), (k, t, ttl) -> {
            throw new IllegalStateException("redis unavailable");
        })) {
            pause(60);
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isFalse();
        }
    }

    @Test
    void renewalStopsAtItsBoundSoAHungLaunchCannotHoldTheLockForever() throws Exception {
        AtomicInteger renewals = new AtomicInteger();
        try (var lease = SandboxLaunchLease.hold("lock", "token", 120, FAST, Duration.ofMillis(50),
            (k, t, ttl) -> renewals.incrementAndGet() > 0)) {
            pause(120);
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isFalse();
            int stopped = renewals.get();
            pause(50);
            assertThat(renewals.get()).isEqualTo(stopped);
        }
    }

    @Test
    void theCheckRenewsNowSoAnUnnoticedLossIsCaught() {
        // The periodic renewal has not run yet; the check itself must ask the lock.
        try (var lease = SandboxLaunchLease.hold("lock", "token", 120, Duration.ofMinutes(1), Duration.ofMinutes(5),
            (k, t, ttl) -> false)) {
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isFalse();
        }
    }

    @Test
    void outsideALeaseALaunchCountsAsHoldingAndClosingRestoresTheOuterLease() {
        assertThat(SandboxLaunchLease.currentLaunchHeld()).isTrue();
        try (var outer = SandboxLaunchLease.hold("outer", "a", 120, Duration.ofMinutes(1), Duration.ofMinutes(5),
            (k, t, ttl) -> "outer".equals(k))) {
            try (var inner = SandboxLaunchLease.hold("inner", "b", 120, Duration.ofMinutes(1), Duration.ofMinutes(5),
                (k, t, ttl) -> false)) {
                assertThat(SandboxLaunchLease.currentLaunchHeld()).isFalse();
            }
            assertThat(SandboxLaunchLease.currentLaunchHeld()).isTrue();
        }
        assertThat(SandboxLaunchLease.currentLaunchHeld()).isTrue();
    }
}
