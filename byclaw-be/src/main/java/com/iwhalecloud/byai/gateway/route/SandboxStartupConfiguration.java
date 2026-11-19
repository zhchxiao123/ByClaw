package com.iwhalecloud.byai.gateway.route;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class SandboxStartupConfiguration {
    static final int STARTUP_THREADS = 32;

    /**
     * Startup RPCs must never run on the waiting request thread, even under saturation.
     * A thread pool only grows past its core size once its queue is full, so core equals max here:
     * with core 4 and a 128-slot queue, a burst of first messages ran four platform calls at a time and
     * the queued starts passed their startup budget. Idle threads time out.
     */
    @Bean("sandboxStartupExecutor")
    public ThreadPoolTaskExecutor sandboxStartupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(STARTUP_THREADS);
        executor.setMaxPoolSize(STARTUP_THREADS);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("sandbox-startup-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
