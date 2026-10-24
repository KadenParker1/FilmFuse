package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * The one-off seed ({@code filmfuse.catalog.seed.run-on-startup=true}, set by the {@code seed}
 * profile): runs {@link CatalogSeedService#seedAll} once the context is up and logs the report. It
 * takes the cross-process {@link CatalogJobLock} first - if another catalog job (the deployed app's
 * top-up, refresh or enrichment, or another seed) holds it, the seed does not start and reports
 * {@code ALREADY_RUNNING}. The run is recorded in {@code app.job_runs} as
 * {@value JobNames#CATALOG_SEED}, triggered by {@value #TRIGGERED_BY}. Without a web server
 * ({@code spring.main.web-application-type=none}, as under {@code seed}) it then shuts the application
 * down with exit code 0 for a completed run and 1 otherwise; with a web server it leaves the
 * application running.
 */
public class CatalogSeedRunner implements ApplicationRunner {

    static final String TRIGGERED_BY = "cli";

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedRunner.class);

    private final CatalogSeedService seeder;
    private final CatalogJobLock lock;
    private final JobRunRecorder recorder;
    private final boolean exitAfterRun;
    private final IntConsumer exit;

    public CatalogSeedRunner(CatalogSeedService seeder, CatalogJobLock lock, JobRunRecorder recorder,
                             ConfigurableApplicationContext context, Environment environment) {
        this(seeder, lock, recorder,
                "none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "")),
                code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    CatalogSeedRunner(CatalogSeedService seeder, CatalogJobLock lock, JobRunRecorder recorder, boolean exitAfterRun,
                      IntConsumer exit) {
        this.seeder = seeder;
        this.lock = lock;
        this.recorder = recorder;
        this.exitAfterRun = exitAfterRun;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedReport report = lock.tryRun(() -> recorder.run(JobNames.CATALOG_SEED, TRIGGERED_BY, seeder::seedAll,
                        result -> new JobRunRecorder.Finish(result.outcome().toJobOutcome(), result.toSummary())))
                .orElseGet(() -> {
                    log.error("Catalog seed not started: another catalog job (top-up, refresh, enrichment or a "
                            + "seed) holds the catalog lock - try again when it has finished");
                    recorder.recordSkipped(JobNames.CATALOG_SEED, TRIGGERED_BY, "another catalog job is running");
                    return SeedReport.notStarted();
                });
        log.info("{}", report);
        if (exitAfterRun) {
            exit.accept(report.outcome() == SeedReport.Outcome.COMPLETED ? 0 : 1);
        }
    }
}
