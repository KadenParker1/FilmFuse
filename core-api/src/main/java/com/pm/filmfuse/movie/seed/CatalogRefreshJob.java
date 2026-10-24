package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The weekly catalog refresh as a job ({@link CatalogRefreshService} with the effective
 * {@link CatalogRefreshSettings}), under the cross-process {@link CatalogJobLock}, recorded in
 * {@code app.job_runs} as {@value JobNames#CATALOG_REFRESH}. Scheduled by {@link CatalogJobsConfig}
 * (default Sundays 05:00 UTC; single replica); {@link #runNow} is the manual entry point.
 */
@Component
public class CatalogRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogRefreshJob.class);

    private final CatalogRefreshService refresher;
    private final CatalogRefreshSettings settings;
    private final CatalogJobLock lock;
    private final JobRunRecorder recorder;

    public CatalogRefreshJob(CatalogRefreshService refresher, CatalogRefreshSettings settings, CatalogJobLock lock,
                             JobRunRecorder recorder) {
        this.refresher = refresher;
        this.settings = settings;
        this.lock = lock;
        this.recorder = recorder;
    }

    /**
     * Runs a refresh now, whatever the runtime {@code enabled} setting says.
     *
     * @param triggeredBy who asked (stored in the job history); null = the scheduler
     * @return the report; {@code ALREADY_RUNNING} when another catalog job holds the lock
     */
    public RefreshReport runNow(String triggeredBy) {
        CatalogRefreshSettings.Settings current = settings.current();
        return lock.tryRun(() -> recorder.run(JobNames.CATALOG_REFRESH, triggeredBy, () -> refresher.refresh(current),
                        report -> new JobRunRecorder.Finish(report.outcome().toJobOutcome(), report.toSummary())))
                .orElseGet(() -> {
                    log.info("Catalog refresh skipped: another catalog job is running");
                    recorder.recordSkipped(JobNames.CATALOG_REFRESH, triggeredBy, "another catalog job is running");
                    return RefreshReport.notStarted(Outcome.ALREADY_RUNNING);
                });
    }

    /** The scheduler's entry: honours the runtime switch, and never throws. */
    void scheduledRun() {
        if (!settings.current().refreshEnabled()) {
            log.info("Catalog refresh paused (refresh disabled in the effective settings)");
            return;
        }
        try {
            runNow(null);
        } catch (RuntimeException ex) {
            log.error("Catalog refresh failed", ex);
        }
    }
}
