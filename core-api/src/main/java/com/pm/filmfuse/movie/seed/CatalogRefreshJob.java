package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The weekly catalog refresh as a job ({@link CatalogRefreshService} with the effective
 * {@link CatalogRefreshSettings}), under the cross-process {@link CatalogJobLock}, recorded in
 * {@code app.job_runs} as {@value JobNames#CATALOG_REFRESH}. Scheduled by {@link CatalogJobsConfig}
 * (default Sundays 05:00 UTC; single replica); {@link #runNow} is the manual entry point.
 */
@Component
public class CatalogRefreshJob {

    /** Scheduled runs: attempts at the catalog lock before the run is recorded as skipped. */
    static final int LOCK_ATTEMPTS = 10;
    static final Duration LOCK_RETRY_DELAY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(CatalogRefreshJob.class);

    private final CatalogRefreshService refresher;
    private final CatalogRefreshSettings settings;
    private final CatalogJobLock lock;
    private final JobRunRecorder recorder;
    private final Sleeper sleeper;

    @Autowired
    public CatalogRefreshJob(CatalogRefreshService refresher, CatalogRefreshSettings settings, CatalogJobLock lock,
                             JobRunRecorder recorder) {
        this(refresher, settings, lock, recorder, Sleeper.SYSTEM);
    }

    CatalogRefreshJob(CatalogRefreshService refresher, CatalogRefreshSettings settings, CatalogJobLock lock,
                      JobRunRecorder recorder, Sleeper sleeper) {
        this.refresher = refresher;
        this.settings = settings;
        this.lock = lock;
        this.recorder = recorder;
        this.sleeper = sleeper;
    }

    /**
     * Runs a refresh now, whatever the runtime {@code enabled} setting says.
     *
     * @param triggeredBy who asked (stored in the job history); null = the scheduler
     * @return the report; {@code ALREADY_RUNNING} when another catalog job holds the lock
     */
    public RefreshReport runNow(String triggeredBy) {
        return tryLocked(triggeredBy).orElseGet(() -> skipped(triggeredBy));
    }

    /** One attempt under the lock: empty when another catalog job holds it (nothing recorded). */
    private Optional<RefreshReport> tryLocked(String triggeredBy) {
        CatalogRefreshSettings.Settings current = settings.current();
        return lock.tryRun(() -> recorder.run(JobNames.CATALOG_REFRESH, triggeredBy, () -> refresher.refresh(current),
                        report -> new JobRunRecorder.Finish(report.outcome().toJobOutcome(), report.toSummary())));
    }

    private RefreshReport skipped(String triggeredBy) {
        log.info("Catalog refresh skipped: another catalog job is running");
        recorder.recordSkipped(JobNames.CATALOG_REFRESH, triggeredBy, "another catalog job is running");
        return RefreshReport.notStarted(Outcome.ALREADY_RUNNING);
    }

    /**
     * The scheduler's entry: honours the runtime switch, and never throws. When another catalog job
     * holds the lock (typically a {@code CatalogEnrichmentJob} tick, every 10 minutes) it tries again
     * every {@link #LOCK_RETRY_DELAY}, {@link #LOCK_ATTEMPTS} times in all, before recording the run
     * as skipped - a scheduled run that lost a few-second race would otherwise wait for its next cron.
     */
    void scheduledRun() {
        try {
            if (!settings.current().refreshEnabled()) {
                log.info("Catalog refresh paused (refresh disabled in the effective settings)");
                return;
            }
            for (int attempt = 1; ; attempt++) {
                if (tryLocked(null).isPresent()) {
                    return;
                }
                if (attempt >= LOCK_ATTEMPTS) {
                    skipped(null);
                    return;
                }
                sleeper.sleep(LOCK_RETRY_DELAY);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Catalog refresh interrupted while waiting for the catalog lock");
        } catch (RuntimeException ex) {
            log.error("Catalog refresh failed", ex);
        }
    }
}
