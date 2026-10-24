package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Properties always; the one-off {@link CatalogSeedRunner} only with
 * {@code filmfuse.catalog.seed.run-on-startup=true}. The nightly top-up and the weekly refresh are
 * scheduled by {@link CatalogJobsConfig}.
 */
@Configuration
@EnableConfigurationProperties(CatalogSeedProperties.class)
public class CatalogSeedConfig {

    /** Downloads TMDB's daily id export over plain HTTP(S); tests construct the seeder with their own. */
    @Bean
    ExportFetcher catalogExportFetcher() {
        return ExportFetcher.http();
    }

    @Bean
    TmdbIdExport tmdbIdExport(ExportFetcher catalogExportFetcher, CatalogSeedProperties properties) {
        return new TmdbIdExport(catalogExportFetcher, properties.export());
    }

    @Bean
    @ConditionalOnProperty(name = "filmfuse.catalog.seed.run-on-startup", havingValue = "true")
    CatalogSeedRunner catalogSeedRunner(CatalogSeedService seeder, CatalogJobLock lock, JobRunRecorder recorder,
                                        ConfigurableApplicationContext context, Environment environment) {
        return new CatalogSeedRunner(seeder, lock, recorder, context, environment);
    }
}
