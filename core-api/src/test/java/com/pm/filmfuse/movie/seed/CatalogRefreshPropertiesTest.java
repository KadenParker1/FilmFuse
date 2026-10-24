package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.seed.CatalogRefreshProperties.Step;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** filmfuse.catalog.refresh.* defaults and bounds, and the effective-settings record built from them. */
class CatalogRefreshPropertiesTest {

    @Test
    void defaults() {
        CatalogRefreshProperties p = CatalogRefreshProperties.defaults();

        assertThat(p.enabled()).isTrue();
        assertThat(p.cron()).isEqualTo("0 0 5 * * SUN");
        assertThat(p.steps()).containsExactly(Step.values());
        assertThat(p.mainTierSize()).isEqualTo(20_000);
        assertThat(p.refreshAfter()).isEqualTo(Duration.ofDays(6));
        assertThat(p.newReleasePagesPerYear()).isEqualTo(20);
        assertThat(p.newReleaseMinVotes()).isEqualTo(5);
        assertThat(p.rejectRecheckLimit()).isEqualTo(5_000);
        assertThat(p.changesDays()).isEqualTo(7);
        assertThat(p.changesMaxPages()).isEqualTo(500);
        assertThat(p.maxConsecutiveFailures()).isEqualTo(25);
    }

    @Test
    void outOfRangeValuesAreBounded() {
        CatalogRefreshProperties p = new CatalogRefreshProperties(false, " ", Arrays.asList(Step.CHANGES, null,
                Step.CHANGES), -1, Duration.ofDays(-1), 9_999, -3, -1, 99, 9_999, 0);

        assertThat(p.enabled()).isFalse();
        assertThat(p.cron()).isEqualTo(CatalogRefreshProperties.DEFAULT_CRON);
        assertThat(p.steps()).containsExactly(Step.CHANGES);
        assertThat(p.mainTierSize()).isEqualTo(20_000);
        assertThat(p.refreshAfter()).isEqualTo(Duration.ofDays(6));
        assertThat(p.newReleasePagesPerYear()).isEqualTo(500);
        assertThat(p.newReleaseMinVotes()).isEqualTo(5);
        assertThat(p.changesDays()).isEqualTo(14);
        assertThat(p.changesMaxPages()).isEqualTo(500);
        assertThat(p.maxConsecutiveFailures()).isEqualTo(25);
        assertThat(new CatalogRefreshProperties(null, null, List.of(), 0, null, 0, 0, 0, 1, 0, 1).mainTierSize())
                .isZero();
    }

    @Test
    void settingsCombineTheTopUpAndTheRefreshAndStayInBounds() {
        Settings s = Settings.of(new CatalogSeedProperties.TopUp(false, "0 1 2 * * *", 70),
                CatalogRefreshProperties.defaults());

        assertThat(s.topUpEnabled()).isFalse();
        assertThat(s.topUpCron()).isEqualTo("0 1 2 * * *");
        assertThat(s.topUpPages()).isEqualTo(70);
        assertThat(s.refreshEnabled()).isTrue();
        assertThat(s.runs(Step.MAIN_TIER)).isTrue();

        Settings wild = new Settings(true, "c", 9_999, true, "c", null, -5, null, 9_999, -1, -1, 0, 9_999, 0);
        assertThat(wild.topUpPages()).isEqualTo(500);
        assertThat(wild.steps()).isEqualTo(EnumSet.noneOf(Step.class));
        assertThat(wild.mainTierSize()).isZero();
        assertThat(wild.refreshAfter()).isEqualTo(Duration.ZERO);
        assertThat(wild.newReleasePagesPerYear()).isEqualTo(500);
        assertThat(wild.changesDays()).isEqualTo(1);
        assertThat(wild.changesMaxPages()).isEqualTo(500);
        assertThat(wild.maxConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void theTopUpDefaultsToFiftyPagesAboutAThousandMovies() {
        assertThat(new CatalogSeedProperties.TopUp(null, null, null).pages()).isEqualTo(50);
        assertThat(new CatalogSeedProperties.TopUp(null, null, 0).pages()).isEqualTo(50);
    }
}
