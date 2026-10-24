package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.catalog.refresh.*} - the weekly catalog refresh ({@link CatalogRefreshService},
 * scheduled by {@link CatalogJobsConfig}). Request pacing, retries, the 401 limit, the size guard and
 * the quality bar are the seeder's ({@code filmfuse.catalog.seed.*}). Read through
 * {@link CatalogRefreshSettings}, so a later admin settings table can override them at runtime.
 *
 * @param enabled                whether the job is scheduled in this process (default true; false
 *                               under the test and seed profiles)
 * @param cron                   when, UTC (default Sundays 05:00)
 * @param steps                  which steps run, in this fixed order: {@code main-tier},
 *                               {@code new-releases}, {@code rejects}, {@code changes} (default all)
 * @param mainTierSize           the main tier: the top n stored movies by popularity, all re-fetched
 *                               weekly (default 20,000)
 * @param refreshAfter           a movie whose details were fetched less than this long ago is skipped
 *                               by every step (default 6 days - below the weekly cadence, so the
 *                               main tier is always refreshed, but a re-run the same week skips)
 * @param newReleasePagesPerYear discover pages (20 movies each) for this year and last year (default 20)
 * @param newReleaseMinVotes     {@code vote_count.gte} for the new-release discover (default 5)
 * @param rejectRecheckLimit     at most this many expired quality rejects re-checked per run (default 5,000)
 * @param changesDays            the long-tail change window, days back from today (default 7; TMDB
 *                               allows at most 14)
 * @param changesMaxPages        change-list pages read at most, 100 ids each (default 500)
 * @param maxConsecutiveFailures this many movies in a row failing after their retries means the
 *                               catalog is down: the run stops (ABORTED) instead of crawling on
 *                               (default 25)
 */
@ConfigurationProperties(prefix = "filmfuse.catalog.refresh")
public record CatalogRefreshProperties(
        Boolean enabled,
        String cron,
        List<Step> steps,
        Integer mainTierSize,
        Duration refreshAfter,
        Integer newReleasePagesPerYear,
        Integer newReleaseMinVotes,
        Integer rejectRecheckLimit,
        Integer changesDays,
        Integer changesMaxPages,
        Integer maxConsecutiveFailures) {

    public static final String DEFAULT_CRON = "0 0 5 * * SUN";
    /** TMDB's {@code /movie/changes} refuses longer windows. */
    public static final int MAX_CHANGES_DAYS = 14;

    /** The refresh steps, always run in this order. */
    public enum Step { MAIN_TIER, NEW_RELEASES, REJECTS, CHANGES }

    public CatalogRefreshProperties {
        enabled = enabled == null || enabled;
        cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron.strip();
        steps = steps == null || steps.stream().allMatch(Objects::isNull) ? List.of(Step.values())
                : List.copyOf(steps.stream().filter(Objects::nonNull).distinct().toList());
        mainTierSize = mainTierSize == null || mainTierSize < 0 ? 20_000 : mainTierSize;
        refreshAfter = refreshAfter == null || refreshAfter.isNegative() ? Duration.ofDays(6) : refreshAfter;
        newReleasePagesPerYear = newReleasePagesPerYear == null || newReleasePagesPerYear < 0 ? 20
                : Math.min(newReleasePagesPerYear, MovieCatalogClient.MAX_PAGE);
        newReleaseMinVotes = newReleaseMinVotes == null || newReleaseMinVotes < 0 ? 5 : newReleaseMinVotes;
        rejectRecheckLimit = rejectRecheckLimit == null || rejectRecheckLimit < 0 ? 5_000 : rejectRecheckLimit;
        changesDays = changesDays == null || changesDays < 1 ? 7 : Math.min(changesDays, MAX_CHANGES_DAYS);
        changesMaxPages = changesMaxPages == null || changesMaxPages < 0 ? 500
                : Math.min(changesMaxPages, MovieCatalogClient.MAX_PAGE);
        maxConsecutiveFailures = maxConsecutiveFailures == null || maxConsecutiveFailures < 1 ? 25
                : maxConsecutiveFailures;
    }

    /** All defaults. */
    public static CatalogRefreshProperties defaults() {
        return new CatalogRefreshProperties(null, null, null, null, null, null, null, null, null, null, null);
    }

    public Set<Step> stepSet() {
        return steps.isEmpty() ? EnumSet.noneOf(Step.class) : EnumSet.copyOf(steps);
    }
}
