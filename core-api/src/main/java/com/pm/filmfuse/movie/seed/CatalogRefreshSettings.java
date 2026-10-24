package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.seed.CatalogRefreshProperties.Step;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * The effective settings of the catalog freshness jobs (nightly top-up, weekly refresh), read once at
 * the start of every run and by the schedulers' triggers before every next execution. The default,
 * {@link FromProperties}, reads {@code filmfuse.catalog.seed.top-up.*} and
 * {@code filmfuse.catalog.refresh.*}; a later admin settings table only has to provide a {@code @Primary}
 * bean of this type (see {@link CatalogJobsConfig}) to change sizes, cron or pause a job without a
 * restart.
 *
 * <p>The {@code enabled} properties still decide whether a job is scheduled in this process at all
 * (off under the test and seed profiles); {@link Settings#topUpEnabled} / {@link Settings#refreshEnabled}
 * pause a scheduled job at runtime. A manual {@code runNow} ignores both.
 */
@FunctionalInterface
public interface CatalogRefreshSettings {

    Settings current();

    /**
     * @param topUpCron    UTC cron of the nightly top-up
     * @param topUpPages   popular pages 1..n of the top-up (default 50, about 1,000 movies)
     * @param refreshCron  UTC cron of the weekly refresh
     * @param steps        refresh steps to run
     * @param refreshAfter skip rule of every refresh step
     */
    record Settings(boolean topUpEnabled, String topUpCron, int topUpPages, boolean refreshEnabled,
                    String refreshCron, Set<Step> steps, int mainTierSize, Duration refreshAfter,
                    int newReleasePagesPerYear, int newReleaseMinVotes, int rejectRecheckLimit, int changesDays,
                    int changesMaxPages, int maxConsecutiveFailures) {

        public Settings {
            topUpPages = Math.max(1, Math.min(topUpPages, MovieCatalogClient.MAX_PAGE));
            steps = steps == null || steps.isEmpty() ? EnumSet.noneOf(Step.class) : EnumSet.copyOf(steps);
            mainTierSize = Math.max(0, mainTierSize);
            refreshAfter = refreshAfter == null || refreshAfter.isNegative() ? Duration.ZERO : refreshAfter;
            newReleasePagesPerYear = Math.max(0, Math.min(newReleasePagesPerYear, MovieCatalogClient.MAX_PAGE));
            newReleaseMinVotes = Math.max(0, newReleaseMinVotes);
            rejectRecheckLimit = Math.max(0, rejectRecheckLimit);
            changesDays = Math.max(1, Math.min(changesDays, CatalogRefreshProperties.MAX_CHANGES_DAYS));
            changesMaxPages = Math.max(0, Math.min(changesMaxPages, MovieCatalogClient.MAX_PAGE));
            maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        }

        public static Settings of(CatalogSeedProperties.TopUp topUp, CatalogRefreshProperties refresh) {
            return new Settings(topUp.enabled(), topUp.cron(), topUp.pages(), refresh.enabled(), refresh.cron(),
                    refresh.stepSet(), refresh.mainTierSize(), refresh.refreshAfter(), refresh.newReleasePagesPerYear(),
                    refresh.newReleaseMinVotes(), refresh.rejectRecheckLimit(), refresh.changesDays(),
                    refresh.changesMaxPages(), refresh.maxConsecutiveFailures());
        }

        public boolean runs(Step step) {
            return steps.contains(step);
        }
    }

    /** The default: the bound properties, unchanged for the life of the process. */
    final class FromProperties implements CatalogRefreshSettings {

        private final Settings settings;

        public FromProperties(CatalogSeedProperties seed, CatalogRefreshProperties refresh) {
            this.settings = Settings.of(seed.topUp(), refresh);
        }

        @Override
        public Settings current() {
            return settings;
        }
    }
}
