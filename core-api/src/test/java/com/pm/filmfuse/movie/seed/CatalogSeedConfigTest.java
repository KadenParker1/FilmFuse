package com.pm.filmfuse.movie.seed;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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

    @Test
    void theTopUpDoesNotHaveTheSchedulerToItself() {
        // application.properties: a long top-up must not block the other jobs (vector refresh every 15 s).
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(ThreadPoolTaskScheduler.class)
                        .getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void bulkSeedingSettingsBindFromProperties() {
        runner.withPropertyValues("filmfuse.catalog.seed.sources=popular,EXPORT,discover",
                        "filmfuse.catalog.seed.max-database-mb=300",
                        "filmfuse.catalog.seed.export.max-movies=400000",
                        "filmfuse.catalog.seed.export.base-url=https://mirror.test/exports/",
                        "filmfuse.catalog.seed.discover.from-year=1990",
                        "filmfuse.catalog.seed.discover.to-year=",
                        "filmfuse.catalog.seed.quality.sources=export",
                        "filmfuse.catalog.seed.quality.require-runtime=false",
                        "filmfuse.catalog.seed.quality.reject-ttl=P14D")
                .run(context -> {
                    CatalogSeedProperties p = context.getBean(CatalogSeedProperties.class);
                    assertThat(p.sources()).containsExactly(CatalogSeedProperties.Source.POPULAR,
                            CatalogSeedProperties.Source.EXPORT, CatalogSeedProperties.Source.DISCOVER);
                    assertThat(p.maxDatabaseMb()).isEqualTo(300);
                    assertThat(p.export().maxMovies()).isEqualTo(400_000);
                    assertThat(p.export().baseUrl()).isEqualTo("https://mirror.test/exports");
                    assertThat(p.discover().fromYear()).isEqualTo(1990);
                    assertThat(p.discover().toYear()).isNull();
                    assertThat(p.quality().appliesTo(CatalogSeedProperties.Source.DISCOVER)).isFalse();
                    assertThat(p.quality().requireRuntime()).isFalse();
                    assertThat(p.quality().rejectTtl()).isEqualTo(java.time.Duration.ofDays(14));
                    assertThat(context).hasSingleBean(TmdbIdExport.class);
                    assertThat(context).hasSingleBean(ExportFetcher.class);
                });
    }

    @Test
    void theShippedDefaultsKeepTheOldSourcesAndGuardTheFreeTier() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    CatalogSeedProperties p = context.getBean(CatalogSeedProperties.class);
                    assertThat(p.sources()).containsExactly(CatalogSeedProperties.Source.POPULAR,
                            CatalogSeedProperties.Source.IDS);
                    assertThat(p.requestsPerSecond()).isEqualTo(20d);
                    assertThat(p.maxDatabaseMb()).isEqualTo(450);
                    assertThat(p.export().maxMovies()).isEqualTo(150_000);
                    assertThat(p.export().baseUrl()).isEqualTo("https://files.tmdb.org/p/exports");
                    assertThat(p.discover().toYear()).isNull();
                    assertThat(p.quality().minVotes()).isEqualTo(5);
                    assertThat(p.quality().appliesTo(CatalogSeedProperties.Source.EXPORT)).isTrue();
                    assertThat(p.quality().appliesTo(CatalogSeedProperties.Source.POPULAR)).isFalse();
                });
    }
}
