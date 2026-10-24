package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds dirty interest vectors in the background: every {@code interval} (15 s) it takes the
 * users whose latest taste write is at least {@code debounce} (10 s) old and rebuilds each through
 * {@link VectorBuildService}. Single replica only - nothing here is distributed-locked. Disabled
 * (no {@code @EnableScheduling}) when {@code filmfuse.vectors.refresh.enabled=false}, as under the
 * test profile, where tests call {@link #refreshDue()} directly with the test clock. It ticks every
 * 15 s, so only failed scheduled passes (an exception, or users whose rebuild threw) are recorded in
 * {@code app.job_runs} ({@value JobNames#VECTOR_REFRESH}).
 */
@Component
public class VectorRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(VectorRefreshScheduler.class);

    private final UserVectorRepository vectors;
    private final VectorBuildService builder;
    private final VectorRefreshProperties properties;
    private final Clock clock;
    /** Null = failures are only logged (unit tests). */
    private final JobRunRecorder recorder;

    public VectorRefreshScheduler(UserVectorRepository vectors, VectorBuildService builder,
                                  VectorRefreshProperties properties, Clock clock) {
        this(vectors, builder, properties, clock, null);
    }

    @Autowired
    public VectorRefreshScheduler(UserVectorRepository vectors, VectorBuildService builder,
                                  VectorRefreshProperties properties, Clock clock, JobRunRecorder recorder) {
        this.vectors = vectors;
        this.builder = builder;
        this.properties = properties;
        this.clock = clock;
        this.recorder = recorder;
    }

    /** One pass: users due, rebuilt (new version stored), failed (the rebuild threw). */
    record Pass(int due, int built, int failed) {
    }

    @Scheduled(fixedDelayString = "${filmfuse.vectors.refresh.interval:PT15S}",
            initialDelayString = "${filmfuse.vectors.refresh.interval:PT15S}")
    void tick() {
        // Another job's @EnableScheduling also schedules this method: honour our own switch.
        if (!properties.enabled()) {
            return;
        }
        Instant started = clock.instant();
        try {
            Pass pass = refreshPass();
            if (pass.failed() > 0) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("due", pass.due());
                summary.put("built", pass.built());
                summary.put("failed", pass.failed());
                recordFailure(started, summary);
            }
        } catch (RuntimeException ex) {
            log.error("Vector refresh pass failed", ex);
            recordFailure(started, JobRunRecorder.errorSummary(ex));
        }
    }

    private void recordFailure(Instant started, Map<String, Object> summary) {
        if (recorder != null) {
            recorder.recordFinished(JobNames.VECTOR_REFRESH, null, started, JobOutcome.FAILED, summary);
        }
    }

    /**
     * One pass: rebuilds every user dirty for at least the debounce (up to {@code batchSize}). One
     * user's failure never stops the others.
     *
     * @return how many new versions were stored
     */
    public int refreshDue() {
        return refreshPass().built();
    }

    Pass refreshPass() {
        List<UUID> due = vectors.findDue(clock.instant().minus(properties.debounce()), properties.batchSize());
        int built = 0;
        int failed = 0;
        for (UUID userId : due) {
            try {
                if (builder.rebuildIfDirty(userId).isPresent()) {
                    built++;
                }
            } catch (RuntimeException ex) {
                log.error("Vector rebuild failed for {}", userId, ex);
                failed++;
            }
        }
        if (!due.isEmpty()) {
            log.info("Vector refresh: {} due, {} rebuilt", due.size(), built);
        }
        return new Pass(due.size(), built, failed);
    }
}
