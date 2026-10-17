package com.pm.filmfuse.movie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Background catalog enrichment ({@link CatalogEnrichmentService#enrich}, {@code batch-size} movies
 * every {@code interval}). Only scheduled when {@code filmfuse.catalog.enrichment.enabled} (default
 * true; false under the test profile). Single replica only.
 */
@Configuration
@EnableConfigurationProperties(CatalogEnrichmentProperties.class)
public class CatalogEnrichmentJob {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.catalog.enrichment.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {

        private static final Logger log = LoggerFactory.getLogger(CatalogEnrichmentJob.class);

        private final CatalogEnrichmentService enrichment;
        private final CatalogEnrichmentProperties properties;

        Scheduling(CatalogEnrichmentService enrichment, CatalogEnrichmentProperties properties) {
            this.enrichment = enrichment;
            this.properties = properties;
        }

        @Scheduled(fixedDelayString = "${filmfuse.catalog.enrichment.interval:PT10M}",
                initialDelayString = "${filmfuse.catalog.enrichment.interval:PT10M}")
        void tick() {
            try {
                enrichment.enrich(properties.batchSize());
            } catch (RuntimeException ex) {
                log.error("Catalog enrichment run failed", ex);
            }
        }
    }
}
