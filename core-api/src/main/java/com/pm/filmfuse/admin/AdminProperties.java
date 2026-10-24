package com.pm.filmfuse.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.admin.*} - the admin portal.
 *
 * @param metricsCacheTtl        how long a computed metrics block (catalog, usage, health) is reused
 *                               (default 30 s; 0 = never cached, as in tests)
 * @param maxDatabaseMbCeiling   the hard upper bound an admin may set {@code catalog.max-database-mb} to
 *                               (default 480 - under the Supabase free tier's 500 MB; raise it on Pro)
 * @param databaseLimitMb        the plan's database size limit shown beside the current size (default
 *                               500 = Supabase free tier)
 * @param mlHealthTimeout        read timeout of the ML health probe (default 2 s)
 */
@ConfigurationProperties(prefix = "filmfuse.admin")
public record AdminProperties(Duration metricsCacheTtl, Integer maxDatabaseMbCeiling, Integer databaseLimitMb,
                              Duration mlHealthTimeout) {

    public static final int MIN_DATABASE_MB = 50;

    public AdminProperties {
        metricsCacheTtl = metricsCacheTtl == null || metricsCacheTtl.isNegative() ? Duration.ofSeconds(30)
                : metricsCacheTtl;
        maxDatabaseMbCeiling = maxDatabaseMbCeiling == null || maxDatabaseMbCeiling < MIN_DATABASE_MB ? 480
                : maxDatabaseMbCeiling;
        databaseLimitMb = databaseLimitMb == null || databaseLimitMb < 1 ? 500 : databaseLimitMb;
        mlHealthTimeout = mlHealthTimeout == null || mlHealthTimeout.isNegative() || mlHealthTimeout.isZero()
                ? Duration.ofSeconds(2) : mlHealthTimeout;
    }
}
