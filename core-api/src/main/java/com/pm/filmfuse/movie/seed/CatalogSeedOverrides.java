package com.pm.filmfuse.movie.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime overrides of the seeder's request discipline and quality bar, applied by
 * {@link CatalogSeedService} and {@link CatalogRefreshService} at the start of every run (so a change
 * applies from the next run, without a restart). The admin portal provides the implementation
 * ({@code admin.settings.DbCatalogSeedOverrides}); without one the configured properties apply as they are.
 *
 * <p>Only these may change at runtime: {@code requests-per-second}, {@code max-database-mb} and
 * {@code quality.min-votes / require-poster / require-overview}. Everything else (sources, retries,
 * the export and discover settings) stays as configured.
 *
 * <p>The overrides win over the configuration, command-line arguments included: a seed CLI run
 * against a database with admin settings uses the stored rate, size guard and quality bar. Each
 * service logs a warning naming every value an override changed ({@link #changes}), so a
 * {@code --filmfuse.catalog.seed.max-database-mb=7000} that an admin override replaced is visible.
 */
@FunctionalInterface
public interface CatalogSeedOverrides {

    /** No overrides: the configured properties. */
    CatalogSeedOverrides NONE = configured -> configured;

    /** The properties a run should use, given the configured ones. Must not throw. */
    CatalogSeedProperties apply(CatalogSeedProperties configured);

    /**
     * The overridable values that differ between {@code configured} and {@code effective}, as
     * {@code "name configured -> effective"}; empty when the overrides changed nothing.
     */
    static List<String> changes(CatalogSeedProperties configured, CatalogSeedProperties effective) {
        List<String> changes = new ArrayList<>();
        change(changes, "requests-per-second", configured.requestsPerSecond(), effective.requestsPerSecond());
        change(changes, "max-database-mb", configured.maxDatabaseMb(), effective.maxDatabaseMb());
        change(changes, "quality.min-votes", configured.quality().minVotes(), effective.quality().minVotes());
        change(changes, "quality.require-poster", configured.quality().requirePoster(),
                effective.quality().requirePoster());
        change(changes, "quality.require-overview", configured.quality().requireOverview(),
                effective.quality().requireOverview());
        return changes;
    }

    private static void change(List<String> changes, String name, Object configured, Object effective) {
        if (!Objects.equals(configured, effective)) {
            changes.add(name + " " + configured + " -> " + effective);
        }
    }

    /** {@code configured} with the runtime-overridable values replaced. */
    static CatalogSeedProperties with(CatalogSeedProperties configured, double requestsPerSecond, int maxDatabaseMb,
                                      int minVotes, boolean requirePoster, boolean requireOverview) {
        CatalogSeedProperties.Quality q = configured.quality();
        CatalogSeedProperties.Quality quality = new CatalogSeedProperties.Quality(q.enabled(), q.sources(),
                requirePoster, q.requireRuntime(), requireOverview, minVotes, q.rejectTtl());
        return new CatalogSeedProperties(configured.runOnStartup(), configured.popularPages(), configured.idFile(),
                requestsPerSecond, configured.maxRetries(), configured.initialBackoff(), configured.refreshAfter(),
                configured.authFailureLimit(), configured.progressEvery(), configured.topUp(), configured.sources(),
                maxDatabaseMb, configured.export(), configured.discover(), quality);
    }
}
