package com.pm.filmfuse.movie.seed;

/**
 * Runtime overrides of the seeder's request discipline and quality bar, applied by
 * {@link CatalogSeedService} and {@link CatalogRefreshService} at the start of every run (so a change
 * applies from the next run, without a restart). The admin portal provides the implementation
 * ({@code admin.settings.DbCatalogSeedOverrides}); without one the configured properties apply as they are.
 *
 * <p>Only these may change at runtime: {@code requests-per-second}, {@code max-database-mb} and
 * {@code quality.min-votes / require-poster / require-overview}. Everything else (sources, retries,
 * the export and discover settings) stays as configured.
 */
@FunctionalInterface
public interface CatalogSeedOverrides {

    /** No overrides: the configured properties. */
    CatalogSeedOverrides NONE = configured -> configured;

    /** The properties a run should use, given the configured ones. Must not throw. */
    CatalogSeedProperties apply(CatalogSeedProperties configured);

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
