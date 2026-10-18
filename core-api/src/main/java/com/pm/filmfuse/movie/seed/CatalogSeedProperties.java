package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.catalog.seed.*} - the catalog seeder ({@link CatalogSeedService}).
 *
 * @param runOnStartup      run a full seed when the application starts ({@link CatalogSeedRunner}); the
 *                          {@code seed} profile sets it, together with no web server, and exits afterwards
 * @param popularPages      popular pages 1..n of a full seed (default and maximum 500 = TMDB's limit,
 *                          about 10,000 movies; 0 = none, e.g. an id-file-only run)
 * @param idFile            optional file of TMDB ids, one per line ({@code #} comments, blanks ignored),
 *                          seeded after the popular pages; blank = none
 * @param requestsPerSecond catalog requests per second, retries included (default 20; TMDB allows ~40-50)
 * @param maxRetries        retries of a failing request (5xx, 429, timeout) with exponential backoff
 *                          before the movie is counted failed (default 3)
 * @param initialBackoff    the first retry's delay, doubled for each further retry (default 1 s)
 * @param refreshAfter      a movie whose details were fetched less than this long ago is skipped
 *                          (default 7 days, as {@code MovieService}'s details TTL)
 * @param authFailureLimit  consecutive rejected-token (401) responses after which the run stops (default 3)
 * @param progressEvery     log progress every n candidates (default 250)
 * @param topUp             the nightly top-up
 */
@ConfigurationProperties(prefix = "filmfuse.catalog.seed")
public record CatalogSeedProperties(
        Boolean runOnStartup,
        Integer popularPages,
        String idFile,
        Double requestsPerSecond,
        Integer maxRetries,
        Duration initialBackoff,
        Duration refreshAfter,
        Integer authFailureLimit,
        Integer progressEvery,
        TopUp topUp) {

    public static final String DEFAULT_CRON = "0 0 4 * * *";

    public CatalogSeedProperties {
        runOnStartup = runOnStartup != null && runOnStartup;
        popularPages = popularPages == null ? MovieCatalogClient.MAX_PAGE
                : Math.max(0, Math.min(popularPages, MovieCatalogClient.MAX_PAGE));
        idFile = idFile == null || idFile.isBlank() ? null : idFile.trim();
        requestsPerSecond = requestsPerSecond == null || !(requestsPerSecond > 0) ? 20d : requestsPerSecond;
        maxRetries = maxRetries == null || maxRetries < 0 ? 3 : maxRetries;
        initialBackoff = initialBackoff == null || initialBackoff.isNegative() ? Duration.ofSeconds(1) : initialBackoff;
        refreshAfter = refreshAfter == null || refreshAfter.isNegative() ? Duration.ofDays(7) : refreshAfter;
        authFailureLimit = authFailureLimit == null || authFailureLimit < 1 ? 3 : authFailureLimit;
        progressEvery = progressEvery == null || progressEvery < 1 ? 250 : progressEvery;
        topUp = topUp == null ? new TopUp(null, null, null) : topUp;
    }

    /**
     * {@code filmfuse.catalog.seed.top-up.*}: re-seeds popular pages 1..{@code pages} to pick up new
     * releases. Not resumable (it always starts at page 1) and never reads the id file.
     *
     * @param enabled whether the job is scheduled (default true; false under the test and seed profiles)
     * @param cron    when, UTC (default 04:00 daily)
     * @param pages   popular pages (default 25, about 500 movies)
     */
    public record TopUp(Boolean enabled, String cron, Integer pages) {

        public TopUp {
            enabled = enabled == null || enabled;
            cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron;
            pages = pages == null || pages < 1 ? 25 : Math.min(pages, MovieCatalogClient.MAX_PAGE);
        }
    }
}
