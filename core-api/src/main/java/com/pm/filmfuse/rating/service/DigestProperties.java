package com.pm.filmfuse.rating.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.digest.*}: the weekly digest email ({@link DigestJob}).
 *
 * @param enabled whether the weekly job is scheduled (false under the test profile; tests call
 *                {@link DigestJob#sendAll()})
 * @param cron    when it runs (Spring cron, UTC); default Mondays 09:00
 */
@ConfigurationProperties(prefix = "filmfuse.digest")
public record DigestProperties(Boolean enabled, String cron) {

    public static final String DEFAULT_CRON = "0 0 9 * * MON";

    public DigestProperties {
        enabled = enabled == null || enabled;
        cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron;
    }
}
