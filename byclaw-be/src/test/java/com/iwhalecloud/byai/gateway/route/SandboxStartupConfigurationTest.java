package com.iwhalecloud.byai.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SandboxStartupConfigurationTest {

    @Test
    void aBurstOfStartsRunsOnAllStartupThreadsAtOnce() throws Exception {
        var executor = new SandboxStartupConfiguration().sandboxStartupExecutor();
        executor.initialize();
        int burst = SandboxStartupConfiguration.STARTUP_THREADS;
        var started = new CountDownLatch(burst);
        var release = new CountDownLatch(1);
        try {
            for (int i = 0; i < burst; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // Each start blocks on its platform call; none of them may wait in the queue behind the others.
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getQueueSize()).isZero();
        }
        finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
