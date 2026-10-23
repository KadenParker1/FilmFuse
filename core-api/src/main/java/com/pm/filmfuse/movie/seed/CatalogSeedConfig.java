package com.pm.filmfuse.movie.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Properties always; the one-off {@link CatalogSeedRunner} only with
 * {@code filmfuse.catalog.seed.run-on-startup=true}; the nightly top-up only when
 * {@code filmfuse.catalog.seed.top-up.enabled} (default true; false under the test and seed
 * profiles). Single replica only (not distributed-locked).
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
    CatalogSeedRunner catalogSeedRunner(CatalogSeedService seeder, ConfigurableApplicationContext context,
                                        Environment environment) {
        return new CatalogSeedRunner(seeder, context, environment);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.catalog.seed.top-up.enabled", havingValue = "true", matchIfMissing = true)
    static class TopUpScheduling {

        private static final Logger log = LoggerFactory.getLogger(CatalogSeedConfig.class);

        private final CatalogSeedService seeder;

        TopUpScheduling(CatalogSeedService seeder) {
            this.seeder = seeder;
        }

        @Scheduled(cron = "${filmfuse.catalog.seed.top-up.cron:" + CatalogSeedProperties.DEFAULT_CRON + "}", zone = "UTC")
        void tick() {
            try {
                seeder.topUp();
            } catch (RuntimeException ex) {
                log.error("Catalog top-up failed", ex);
            }
        }
    }
}
