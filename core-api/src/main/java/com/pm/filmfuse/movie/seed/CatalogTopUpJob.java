package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The nightly top-up as a job: popular pages 1..{@code top-up.pages} (default 50, about the 1,000 most
 * popular movies) through {@link CatalogSeedService#topUp(int)}, under the cross-process
 * {@link CatalogJobLock}, recorded in {@code app.job_runs} as {@value JobNames#CATALOG_TOP_UP}.
 * Scheduled by {@link CatalogJobsConfig}; {@link #runNow} is the manual entry point (a later admin API).
 */
@Component
public class CatalogTopUpJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogTopUpJob.class);

    private final CatalogSeedService seeder;
    private final CatalogRefreshSettings settings;
    private final CatalogJobLock lock;
    private final JobRunRecorder recorder;

    public CatalogTopUpJob(CatalogSeedService seeder, CatalogRefreshSettings settings, CatalogJobLock lock,
                           JobRunRecorder recorder) {
        this.seeder = seeder;
        this.settings = settings;
        this.lock = lock;
        this.recorder = recorder;
    }

    /**
     * Runs a top-up now, whatever the runtime {@code enabled} setting says.
     *
     * @param triggeredBy who asked (stored in the job history); null = the scheduler
     * @return the report; {@code ALREADY_RUNNING} when another catalog job holds the lock
     */
    public SeedReport runNow(String triggeredBy) {
        int pages = settings.current().topUpPages();
        return lock.tryRun(() -> recorder.run(JobNames.CATALOG_TOP_UP, triggeredBy, () -> seeder.topUp(pages),
                        report -> new JobRunRecorder.Finish(report.outcome().toJobOutcome(), report.toSummary())))
                .orElseGet(() -> {
                    log.info("Catalog top-up skipped: another catalog job is running");
                    recorder.recordSkipped(JobNames.CATALOG_TOP_UP, triggeredBy, "another catalog job is running");
                    return SeedReport.notStarted();
                });
    }

    /** The scheduler's entry: honours the runtime switch, and never throws. */
    void scheduledRun() {
        if (!settings.current().topUpEnabled()) {
            log.info("Catalog top-up paused (top-up disabled in the effective settings)");
            return;
        }
        try {
            runNow(null);
        } catch (RuntimeException ex) {
            log.error("Catalog top-up failed", ex);
        }
    }
}
