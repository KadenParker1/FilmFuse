package com.pm.filmfuse.movie.seed;

import java.time.Duration;
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
        assertThat(p.topUp().pages()).isEqualTo(25);
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
        assertThat(p.topUp().pages()).isEqualTo(25);

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
}
