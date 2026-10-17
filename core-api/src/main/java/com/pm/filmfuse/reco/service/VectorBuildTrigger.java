package com.pm.filmfuse.reco.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * Starts a vector rebuild in the background without waiting for it (onboarding completion uses it so
 * the request never blocks on the ML service). Runs on {@link VectorBuildExecutorConfig#EXECUTOR}:
 * two threads and a small queue. When the executor is saturated the request is simply dropped - the
 * row is already dirty, so {@link VectorRefreshScheduler} builds it on its next tick.
 */
@Component
public class VectorBuildTrigger {

    private static final Logger log = LoggerFactory.getLogger(VectorBuildTrigger.class);

    private final TaskExecutor executor;
    private final VectorBuildService builder;

    public VectorBuildTrigger(@Qualifier(VectorBuildExecutorConfig.EXECUTOR) TaskExecutor executor,
                              VectorBuildService builder) {
        this.executor = executor;
        this.builder = builder;
    }

    /** Call after the dirty mark has committed. Never throws. */
    public void requestRebuild(UUID userId) {
        try {
            executor.execute(() -> {
                try {
                    builder.rebuildIfDirty(userId);
                } catch (RuntimeException ex) {
                    log.warn("Background vector build failed for {}; the scheduler will retry", userId, ex);
                }
            });
        } catch (TaskRejectedException ex) {
            log.info("Vector build executor is busy; leaving {} to the scheduler", userId);
        }
    }
}
