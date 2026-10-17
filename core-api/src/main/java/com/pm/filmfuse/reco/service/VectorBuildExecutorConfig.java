package com.pm.filmfuse.reco.service;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The bounded executor for on-demand vector builds (see {@link VectorBuildTrigger}). */
@Configuration
public class VectorBuildExecutorConfig {

    public static final String EXECUTOR = "vectorBuildExecutor";
    static final int THREADS = 2;
    static final int QUEUE = 20;

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor vectorBuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE);
        executor.setThreadNamePrefix("vector-build-");
        // Rejection surfaces as TaskRejectedException; VectorBuildTrigger leaves the user to the scheduler.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
