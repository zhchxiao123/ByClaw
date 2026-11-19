package com.iwhalecloud.byai.gateway.sandbox.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a sandbox launch lock held while its launch runs. The launch's STARTING row commits before the remote call,
 * and a later request may take over a stale row once the lock lapses; renewing the lock (by owner token) keeps a live
 * launch from being taken over. Renewal stops at a bound so a hung remote call cannot lock the user out for good.
 * Once a renewal fails, errs or passes the bound the launch counts as no longer owning the lock: it must then leave
 * the remote instance, cache and endpoint alone, since a successor may hold them.
 */
final class SandboxLaunchLease implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxLaunchLease.class);
    private static final ScheduledExecutorService RENEWALS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sandbox-launch-lease");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<SandboxLaunchLease> CURRENT = new ThreadLocal<>();

    /** Renews a lock by owner token; true while the token still holds it. */
    @FunctionalInterface
    interface Renewer {
        Boolean renew(String key, String token, long ttlSeconds);
    }

    private final String key;
    private final String token;
    private final Renewer renewer;
    private final long ttlSeconds;
    private final long deadlineNanos;
    private final SandboxLaunchLease previous;
    private final ScheduledFuture<?> renewal;
    private volatile boolean lost;

    private SandboxLaunchLease(String key, String token, long ttlSeconds, Duration interval, Duration max,
        Renewer renewer) {
        this.key = key;
        this.token = token;
        this.renewer = renewer;
        this.ttlSeconds = ttlSeconds;
        this.deadlineNanos = System.nanoTime() + max.toNanos();
        this.previous = CURRENT.get();
        long period = Math.max(1, interval.toMillis());
        this.renewal = RENEWALS.scheduleWithFixedDelay(this::renew, period, period, TimeUnit.MILLISECONDS);
        CURRENT.set(this);
    }

    /** Start renewing a launch lock this thread has just acquired; close it before releasing the lock. */
    static SandboxLaunchLease hold(String key, String token, long ttlSeconds, Duration interval, Duration max,
        Renewer renewer) {
        return new SandboxLaunchLease(key, token, ttlSeconds, interval, max, renewer);
    }

    /**
     * Whether the launch on this thread still owns its lock, confirmed by renewing it now: a true answer leaves the lock
     * held for another full expiry, so no other launch can start on this user's sandbox while the caller acts on it.
     * True outside a lease, as before.
     */
    static boolean currentLaunchHeld() {
        SandboxLaunchLease lease = CURRENT.get();
        if (lease == null) {
            return true;
        }
        lease.renew();
        return !lease.lost;
    }

    private synchronized void renew() {
        if (lost) {
            return;
        }
        if (System.nanoTime() - deadlineNanos >= 0) {
            lose("renewal bound reached");
            return;
        }
        try {
            if (!Boolean.TRUE.equals(renewer.renew(key, token, ttlSeconds))) {
                lose("lock no longer held");
            }
        }
        catch (RuntimeException failure) {
            lose("renewal failed: " + failure);
        }
    }

    private void lose(String reason) {
        lost = true;
        renewal.cancel(false);
        LOGGER.warn("沙箱启动锁续期停止，后续不再清理远端实例：key={}，原因：{}", key, reason);
    }

    @Override
    public void close() {
        renewal.cancel(false);
        if (previous == null) {
            CURRENT.remove();
        }
        else {
            CURRENT.set(previous);
        }
    }
}
