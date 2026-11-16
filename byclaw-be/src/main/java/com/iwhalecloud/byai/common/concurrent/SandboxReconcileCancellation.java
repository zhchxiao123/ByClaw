package com.iwhalecloud.byai.common.concurrent;

import java.util.concurrent.CancellationException;

/**
 * Sandbox reconcile completion/cancellation support.
 * @author Codex
 * @since 2026-11-16
 */
public final class SandboxReconcileCancellation {

    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    private SandboxReconcileCancellation() {
    }

    /** Bind the current reconcile worker deadline. */
    public static void setDeadline(long deadline) {
        DEADLINE.set(deadline);
    }

    /** Release the current worker cancellation context. */
    public static void clear() {
        DEADLINE.remove();
    }

    /** Return whether this thread is executing a reconcile scope. */
    public static boolean isActive() {
        return DEADLINE.get() != null;
    }

    /** Recognize cancellation and preserve wrapped interruption signals. */
    public static boolean isCancelled(Throwable failure) {
        Long deadline = DEADLINE.get();
        if (deadline == null) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= deadline;
    }

    /** Stop further work when the worker is cancelled or overdue. */
    public static void check(Throwable failure) {
        if (isCancelled(failure)) {
            throw new CancellationException("Sandbox reconcile cancelled or deadline exceeded");
        }
    }
}
