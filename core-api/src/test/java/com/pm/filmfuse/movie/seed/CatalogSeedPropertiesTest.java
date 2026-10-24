package com.pm.filmfuse.movie.seed;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSeedPropertiesTest {

    @Test
    void defaults() {
        CatalogSeedProperties p = new CatalogSeedProperties(null, null, null, null, null, null, null, null, null, null);

        assertThat(p.runOnStartup()).isFalse();
        assertThat(p.popularPages()).isEqualTo(500);
        assertThat(p.idFile()).isNull();
        assertThat(p.requestsPerSecond()).isEqualTo(20d);
        assertThat(p.maxRetries()).isEqualTo(3);
        assertThat(p.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.refreshAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(p.authFailureLimit()).isEqualTo(3);
        assertThat(p.progressEvery()).isEqualTo(250);
        assertThat(p.topUp().enabled()).isTrue();
        assertThat(p.topUp().cron()).isEqualTo("0 0 4 * * *");
        assertThat(p.topUp().pages()).isEqualTo(50);
    }

    @Test
    void outOfRangeValuesAreNormalised() {
        CatalogSeedProperties p = new CatalogSeedProperties(true, 9_999, "  ", -1d, -1, Duration.ofSeconds(-1),
                Duration.ofDays(-1), 0, 0, new CatalogSeedProperties.TopUp(false, " ", 0));

        assertThat(p.runOnStartup()).isTrue();
        assertThat(p.popularPages()).isEqualTo(500);
        assertThat(p.idFile()).isNull();
        assertThat(p.requestsPerSecond()).isEqualTo(20d);
        assertThat(p.maxRetries()).isEqualTo(3);
        assertThat(p.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.refreshAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(p.authFailureLimit()).isEqualTo(3);
        assertThat(p.progressEvery()).isEqualTo(250);
        assertThat(p.topUp().enabled()).isFalse();
        assertThat(p.topUp().cron()).isEqualTo("0 0 4 * * *");
        assertThat(p.topUp().pages()).isEqualTo(50);

        assertThat(new CatalogSeedProperties(null, -3, " ids.txt ", 2.5, 0, Duration.ZERO, Duration.ZERO, 1, 1,
                new CatalogSeedProperties.TopUp(null, null, 9_999)))
                .satisfies(q -> {
                    assertThat(q.popularPages()).isZero();
                    assertThat(q.idFile()).isEqualTo("ids.txt");
                    assertThat(q.requestsPerSecond()).isEqualTo(2.5);
                    assertThat(q.maxRetries()).isZero();
                    assertThat(q.refreshAfter()).isZero();
                    assertThat(q.topUp().pages()).isEqualTo(500);
                });
    }

    @Test
    void bulkDefaults() {
        CatalogSeedProperties p = new CatalogSeedProperties(null, null, null, null, null, null, null, null, null, null);

        assertThat(p.sources()).containsExactly(CatalogSeedProperties.Source.POPULAR, CatalogSeedProperties.Source.IDS);
        assertThat(p.uses(CatalogSeedProperties.Source.EXPORT)).isFalse();
        assertThat(p.maxDatabaseMb()).isEqualTo(450);
        assertThat(p.export().baseUrl()).isEqualTo("https://files.tmdb.org/p/exports");
        assertThat(p.export().minPopularity()).isZero();
        assertThat(p.export().maxMovies()).isEqualTo(150_000);
        assertThat(p.export().fallbackDays()).isEqualTo(3);
        assertThat(p.export().maxLines()).isEqualTo(5_000_000);
        assertThat(p.export().maxLineLength()).isEqualTo(1_024);
        assertThat(p.export().maxBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(p.discover().fromYear()).isEqualTo(1920);
        assertThat(p.discover().toYear()).isNull();
        assertThat(p.discover().pagesPerYear()).isEqualTo(10);
        assertThat(p.discover().minVotes()).isEqualTo(10);
        assertThat(p.quality().enabled()).isTrue();
        assertThat(p.quality().sources()).containsExactly(CatalogSeedProperties.Source.EXPORT,
                CatalogSeedProperties.Source.DISCOVER);
        assertThat(p.quality().requirePoster()).isTrue();
        assertThat(p.quality().requireRuntime()).isTrue();
        assertThat(p.quality().requireOverview()).isTrue();
        assertThat(p.quality().minVotes()).isEqualTo(5);
        assertThat(p.quality().rejectTtl()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void bulkValuesAreNormalised() {
        CatalogSeedProperties p = new CatalogSeedProperties(null, null, null, null, null, null, null, null, null, null,
                Arrays.asList(CatalogSeedProperties.Source.EXPORT, null, CatalogSeedProperties.Source.EXPORT),
                -5,
                new CatalogSeedProperties.Export(" https://x.test/e// ", Double.NaN, 0, 99, 0, 3, 0L),
                new CatalogSeedProperties.Discover(1200, 1000, 9_999, -1),
                new CatalogSeedProperties.Quality(null, null, null, null, null, -1, Duration.ofDays(-1)));

        assertThat(p.sources()).containsExactly(CatalogSeedProperties.Source.EXPORT);
        assertThat(p.maxDatabaseMb()).isEqualTo(450);
        assertThat(p.export().baseUrl()).isEqualTo("https://x.test/e");
        assertThat(p.export().minPopularity()).isZero();
        assertThat(p.export().maxMovies()).isEqualTo(150_000);
        assertThat(p.export().fallbackDays()).isEqualTo(30);
        assertThat(p.export().maxLines()).isEqualTo(5_000_000);
        assertThat(p.export().maxLineLength()).isEqualTo(1_024);
        assertThat(p.export().maxBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(p.discover().fromYear()).isEqualTo(1920);
        assertThat(p.discover().toYear()).isNull();
        assertThat(p.discover().pagesPerYear()).isEqualTo(500);
        assertThat(p.discover().minVotes()).isEqualTo(10);
        assertThat(p.quality().minVotes()).isEqualTo(5);
        assertThat(p.quality().rejectTtl()).isEqualTo(Duration.ofDays(30));
        CatalogSeedProperties q = new CatalogSeedProperties(null, null, null, null, null, null, null, null, null, null,
                List.of(), 0, null, null, null);
        assertThat(q.sources()).containsExactly(CatalogSeedProperties.Source.POPULAR, CatalogSeedProperties.Source.IDS);
        assertThat(q.maxDatabaseMb()).isZero();
    }
}
