package com.pm.filmfuse.reco.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.vectors.refresh.*}.
 *
 * @param enabled   whether the scheduled tick runs at all (false under the test profile; tests call
 *                  {@link VectorRefreshScheduler#refreshDue()} directly)
 * @param interval  delay between ticks (the {@code @Scheduled} fixed delay reads the same property)
 * @param debounce  how long a user must have been quiet (no new taste write) before a rebuild
 * @param batchSize at most this many users per tick
 */
@ConfigurationProperties(prefix = "filmfuse.vectors.refresh")
public record VectorRefreshProperties(Boolean enabled, Duration interval, Duration debounce, Integer batchSize) {

    public VectorRefreshProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofSeconds(15) : interval;
        debounce = debounce == null ? Duration.ofSeconds(10) : debounce;
        batchSize = batchSize == null || batchSize < 1 ? 50 : batchSize;
    }
}
