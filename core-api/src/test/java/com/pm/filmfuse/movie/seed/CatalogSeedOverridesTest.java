package com.pm.filmfuse.movie.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogSeedOverridesTest {

    /** As a seed CLI run on Supabase Pro would configure it: 40 req/s, a 7 GB guard. */
    private final CatalogSeedProperties cli = new CatalogSeedProperties(null, null, null, 40d, null, null, null, null,
            null, null);

    @Test
    void noOverridesChangeNothing() {
        assertThat(CatalogSeedOverrides.changes(cli, CatalogSeedOverrides.NONE.apply(cli))).isEmpty();
        CatalogSeedProperties same = CatalogSeedOverrides.with(cli, cli.requestsPerSecond(), cli.maxDatabaseMb(),
                cli.quality().minVotes(), cli.quality().requirePoster(), cli.quality().requireOverview());
        assertThat(CatalogSeedOverrides.changes(cli, same)).isEmpty();
    }

    @Test
    void namesEveryValueAnOverrideReplaced() {
        CatalogSeedProperties effective = CatalogSeedOverrides.with(cli, 20, 480, 10, false,
                cli.quality().requireOverview());
        assertThat(CatalogSeedOverrides.changes(cli, effective)).containsExactly(
                "requests-per-second 40.0 -> 20.0",
                "max-database-mb " + cli.maxDatabaseMb() + " -> 480",
                "quality.min-votes " + cli.quality().minVotes() + " -> 10",
                "quality.require-poster true -> false");
    }
}
