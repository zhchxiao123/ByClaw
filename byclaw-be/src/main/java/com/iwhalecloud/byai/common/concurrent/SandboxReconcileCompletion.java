package com.iwhalecloud.byai.common.concurrent;

import com.iwhalecloud.byai.common.datasource.config.CustomerContextHolder;
import com.iwhalecloud.byai.common.login.auth.CurrentUserHolder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Sandbox reconcile completion/cancellation support.
 * @author Codex
 * @since 2026-11-16
 */
public final class SandboxReconcileCompletion {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SandboxReconcileCompletion.class);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(null, runnable, "sandbox-reconcile-completion-" + SEQUENCE.incrementAndGet(), 0, false);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private SandboxReconcileCompletion() { }

    /** Submit local state completion on a clean thread and wait for at most five seconds. */
    public static <T> T complete(Supplier<T> completion) {
        var login = CurrentUserHolder.getLoginInfo();
        String dataSource = CustomerContextHolder.getCustomerType();
        Future<T> future = EXECUTOR.submit(() -> {
            CurrentUserHolder.setLoginInfo(login);
            CustomerContextHolder.setCustomerType(dataSource);
            try {
                return completion.get();
            }
            catch (RuntimeException e) {
                LOG.error("Local sandbox completion failed; durable record retained for reconciliation", e);
                throw e;
            }
            finally {
                CurrentUserHolder.clearLoginInfo();
                CustomerContextHolder.clearCustomerType();
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            LOG.error("Interrupted while awaiting sandbox completion", e);
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("Local sandbox completion continues independently");
            cancelled.initCause(e);
            throw cancelled;
        }
        catch (TimeoutException e) {
            LOG.error("Timed out awaiting sandbox completion; durable record retained", e);
            CancellationException cancelled = new CancellationException("Local sandbox completion pending; reconcile will recover it");
            cancelled.initCause(e);
            throw cancelled;
        }
        catch (ExecutionException e) {
            LOG.error("Sandbox completion task failed", e);
            throw new IllegalStateException("Local sandbox completion failed; durable record retained", e.getCause());
        }
    }
}
