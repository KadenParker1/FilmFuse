package com.pm.filmfuse.movie.seed;

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
 * profile): runs {@link CatalogSeedService#seedAll} once the context is up and logs the report.
 * Without a web server ({@code spring.main.web-application-type=none}, as under {@code seed}) it then
 * shuts the application down with exit code 0 for a completed run and 1 otherwise; with a web server
 * it leaves the application running.
 */
public class CatalogSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedRunner.class);

    private final CatalogSeedService seeder;
    private final boolean exitAfterRun;
    private final IntConsumer exit;

    public CatalogSeedRunner(CatalogSeedService seeder, ConfigurableApplicationContext context, Environment environment) {
        this(seeder, "none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "")),
                code -> System.exit(SpringApplication.exit(context, () -> code)));
    }

    CatalogSeedRunner(CatalogSeedService seeder, boolean exitAfterRun, IntConsumer exit) {
        this.seeder = seeder;
        this.exitAfterRun = exitAfterRun;
        this.exit = exit;
    }

    @Override
    public void run(ApplicationArguments args) {
        SeedReport report = seeder.seedAll();
        log.info("{}", report);
        if (exitAfterRun) {
            exit.accept(report.outcome() == SeedReport.Outcome.COMPLETED ? 0 : 1);
        }
    }
}
