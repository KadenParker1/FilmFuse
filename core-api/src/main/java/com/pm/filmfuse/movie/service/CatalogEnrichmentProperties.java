package com.pm.filmfuse.movie.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.catalog.enrichment.*}.
 *
 * @param enabled   whether the background job runs (false under the test profile)
 * @param interval  delay between runs (default 10 minutes)
 * @param batchSize   movies looked up per run (default 50)
 * @param sparseBelow a movie cache smaller than this is seeded with the catalog's popular pages
 *                    before a short pool is enriched (default 100; 0 = never, as under the test
 *                    profile, whose shared database must not fill up with fixture movies)
 */
@ConfigurationProperties(prefix = "filmfuse.catalog.enrichment")
public record CatalogEnrichmentProperties(Boolean enabled, Duration interval, Integer batchSize, Integer sparseBelow) {

    public CatalogEnrichmentProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofMinutes(10) : interval;
        batchSize = batchSize == null || batchSize < 1 ? 50 : batchSize;
        sparseBelow = sparseBelow == null || sparseBelow < 0 ? 100 : sparseBelow;
    }
}
