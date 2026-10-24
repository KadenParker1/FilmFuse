package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The nightly top-up as a job: popular pages 1..{@code top-up.pages} (default 50, about the 1,000 most
 * popular movies) through {@link CatalogSeedService#topUp(int)}, under the cross-process
 * {@link CatalogJobLock}, recorded in {@code app.job_runs} as {@value JobNames#CATALOG_TOP_UP}.
 * Scheduled by {@link CatalogJobsConfig}; {@link #runNow} is the manual entry point (a later admin API).
 */
@Component
public class CatalogTopUpJob {

    /** Scheduled runs: attempts at the catalog lock before the run is recorded as skipped. */
    static final int LOCK_ATTEMPTS = 10;
    static final Duration LOCK_RETRY_DELAY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(CatalogTopUpJob.class);

    private final CatalogSeedService seeder;
    private final CatalogRefreshSettings settings;
    private final CatalogJobLock lock;
    private final JobRunRecorder recorder;
    private final Sleeper sleeper;

    @Autowired
    public CatalogTopUpJob(CatalogSeedService seeder, CatalogRefreshSettings settings, CatalogJobLock lock,
                           JobRunRecorder recorder) {
        this(seeder, settings, lock, recorder, Sleeper.SYSTEM);
    }

    CatalogTopUpJob(CatalogSeedService seeder, CatalogRefreshSettings settings, CatalogJobLock lock,
                    JobRunRecorder recorder, Sleeper sleeper) {
        this.seeder = seeder;
        this.settings = settings;
        this.lock = lock;
        this.recorder = recorder;
        this.sleeper = sleeper;
    }

    /**
     * Runs a top-up now, whatever the runtime {@code enabled} setting says.
     *
     * @param triggeredBy who asked (stored in the job history); null = the scheduler
     * @return the report; {@code ALREADY_RUNNING} when another catalog job holds the lock
     */
    public SeedReport runNow(String triggeredBy) {
        return tryLocked(triggeredBy).orElseGet(() -> skipped(triggeredBy));
    }

    /** One attempt under the lock: empty when another catalog job holds it (nothing recorded). */
    private Optional<SeedReport> tryLocked(String triggeredBy) {
        int pages = settings.current().topUpPages();
        return lock.tryRun(() -> recorder.run(JobNames.CATALOG_TOP_UP, triggeredBy, () -> seeder.topUp(pages),
                        report -> new JobRunRecorder.Finish(report.outcome().toJobOutcome(), report.toSummary())));
    }

    private SeedReport skipped(String triggeredBy) {
        log.info("Catalog top-up skipped: another catalog job is running");
        recorder.recordSkipped(JobNames.CATALOG_TOP_UP, triggeredBy, "another catalog job is running");
        return SeedReport.notStarted();
    }

    /**
     * The scheduler's entry: honours the runtime switch, and never throws. When another catalog job
     * holds the lock (typically a {@code CatalogEnrichmentJob} tick, every 10 minutes) it tries again
     * every {@link #LOCK_RETRY_DELAY}, {@link #LOCK_ATTEMPTS} times in all, before recording the run
     * as skipped - a scheduled run that lost a few-second race would otherwise wait for its next cron.
     */
    void scheduledRun() {
        try {
            if (!settings.current().topUpEnabled()) {
                log.info("Catalog top-up paused (top-up disabled in the effective settings)");
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
            log.warn("Catalog top-up interrupted while waiting for the catalog lock");
        } catch (RuntimeException ex) {
            log.error("Catalog top-up failed", ex);
        }
    }
}
