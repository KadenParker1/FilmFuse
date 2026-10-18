package com.pm.filmfuse.movie.seed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** filmfuse.catalog.seed.*: nightly top-up at 04:00 UTC unless disabled; the startup runner only on request. */
class CatalogSeedConfigTest {

    private final CatalogSeedService seeder = mock(CatalogSeedService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogSeedConfig.class)
            .withBean(CatalogSeedService.class, () -> seeder);

    @Test
    void theTopUpIsScheduledNightlyByDefaultAndNoStartupSeedRuns() {
        runner.run(context -> {
            var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            CronTask task = (CronTask) tasks.iterator().next().getTask();
            assertThat(task.getExpression()).isEqualTo("0 0 4 * * *");
            task.getRunnable().run();
            verify(seeder).topUp();
            assertThat(context).doesNotHaveBean(CatalogSeedRunner.class);
            CatalogSeedProperties properties = context.getBean(CatalogSeedProperties.class);
            assertThat(properties.topUp().pages()).isEqualTo(25);
            assertThat(properties.popularPages()).isEqualTo(500);
        });
    }

    @Test
    void cronAndPagesAreConfigurableAndAFailureIsSwallowed() {
        when(seeder.topUp()).thenThrow(new IllegalStateException("boom"));
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.cron=0 15 2 * * *",
                        "filmfuse.catalog.seed.top-up.pages=7", "filmfuse.catalog.seed.requests-per-second=5")
                .run(context -> {
                    CronTask task = (CronTask) context.getBean(ScheduledTaskHolder.class)
                            .getScheduledTasks().iterator().next().getTask();
                    assertThat(task.getExpression()).isEqualTo("0 15 2 * * *");
                    task.getRunnable().run();
                    verify(seeder).topUp();
                    CatalogSeedProperties properties = context.getBean(CatalogSeedProperties.class);
                    assertThat(properties.topUp().pages()).isEqualTo(7);
                    assertThat(properties.requestsPerSecond()).isEqualTo(5d);
                });
    }

    @Test
    void disabledMeansNoScheduledTask() {
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }

    @Test
    void runOnStartupRegistersTheRunner() {
        runner.withPropertyValues("filmfuse.catalog.seed.run-on-startup=true", "filmfuse.catalog.seed.top-up.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(CatalogSeedRunner.class));
    }
}
