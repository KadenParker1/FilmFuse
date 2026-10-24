package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
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
 * true; false under the test profile). Each run takes the cross-process {@link CatalogJobLock} and
 * skips quietly while another catalog job (seed, top-up, refresh) holds it. Runs that checked movies
 * or failed are recorded in {@code app.job_runs} ({@value JobNames#CATALOG_ENRICHMENT}); idle runs and
 * lock skips are not (every 10 minutes they would crowd out the history). Single replica only.
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
        private final CatalogJobLock lock;
        private final JobRunRecorder recorder;
        private final Clock clock;

        Scheduling(CatalogEnrichmentService enrichment, CatalogEnrichmentProperties properties, CatalogJobLock lock,
                   JobRunRecorder recorder, Clock clock) {
            this.enrichment = enrichment;
            this.properties = properties;
            this.lock = lock;
            this.recorder = recorder;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "${filmfuse.catalog.enrichment.interval:PT10M}",
                initialDelayString = "${filmfuse.catalog.enrichment.interval:PT10M}")
        void tick() {
            Instant started = clock.instant();
            try {
                boolean ran = lock.tryRun(() -> {
                    int checked = enrichment.enrich(properties.batchSize());
                    if (checked > 0) {
                        recorder.recordFinished(JobNames.CATALOG_ENRICHMENT, null, started, JobOutcome.COMPLETED,
                                Map.of("checked", checked));
                    }
                    return true;
                }).isPresent();
                if (!ran) {
                    log.debug("Catalog enrichment skipped: another catalog job is running");
                }
            } catch (RuntimeException ex) {
                log.error("Catalog enrichment run failed", ex);
                recorder.recordFinished(JobNames.CATALOG_ENRICHMENT, null, started, JobOutcome.FAILED,
                        JobRunRecorder.errorSummary(ex));
            }
        }
    }
}
