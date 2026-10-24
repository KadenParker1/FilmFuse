package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.support.FakeCatalogJobLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** filmfuse.catalog.seed.*: binding and defaults; the startup runner only on request (scheduling: CatalogJobsConfigTest). */
class CatalogSeedConfigTest {

    private final CatalogSeedService seeder = mock(CatalogSeedService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogSeedConfig.class)
            .withBean(CatalogSeedService.class, () -> seeder)
            .withBean(CatalogJobLock.class, FakeCatalogJobLock::new)
            .withBean(JobRunRecorder.class, () -> new JobRunRecorder(mock(JobRunRepository.class),
                    java.time.Clock.systemUTC(), 200));

    @Test
    void noStartupSeedByDefaultAndTheTopUpDefaultsToFiftyPages() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CatalogSeedRunner.class);
            CatalogSeedProperties properties = context.getBean(CatalogSeedProperties.class);
            assertThat(properties.topUp().pages()).isEqualTo(50);
            assertThat(properties.topUp().cron()).isEqualTo("0 0 4 * * *");
            assertThat(properties.popularPages()).isEqualTo(500);
            // Scheduling lives in CatalogJobsConfig now.
            assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
        });
    }

    @Test
    void topUpSettingsBind() {
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.cron=0 15 2 * * *",
                        "filmfuse.catalog.seed.top-up.pages=7", "filmfuse.catalog.seed.requests-per-second=5")
                .run(context -> {
                    CatalogSeedProperties properties = context.getBean(CatalogSeedProperties.class);
                    assertThat(properties.topUp().cron()).isEqualTo("0 15 2 * * *");
                    assertThat(properties.topUp().pages()).isEqualTo(7);
                    assertThat(properties.requestsPerSecond()).isEqualTo(5d);
                });
    }

    @Test
    void runOnStartupRegistersTheRunner() {
        runner.withPropertyValues("filmfuse.catalog.seed.run-on-startup=true", "filmfuse.catalog.seed.top-up.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(CatalogSeedRunner.class));
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
                    assertThat(p.topUp().pages()).isEqualTo(50);
                });
    }
}
