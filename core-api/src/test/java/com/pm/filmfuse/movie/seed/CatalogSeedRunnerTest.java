package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSeedRunnerTest {

    private final CatalogSeedService seeder = mock(CatalogSeedService.class);
    private final List<Integer> exits = new ArrayList<>();

    private static SeedReport report(Outcome outcome) {
        return new SeedReport(outcome, 1, 2, 2, 0, 0, 0, Duration.ZERO);
    }

    @Test
    void aCompletedRunExitsZero() {
        when(seeder.seedAll()).thenReturn(report(Outcome.COMPLETED));

        new CatalogSeedRunner(seeder, true, exits::add).run(new DefaultApplicationArguments());

        verify(seeder).seedAll();
        assertThat(exits).containsExactly(0);
    }

    @Test
    void anAbortedOrInterruptedRunExitsOne() {
        when(seeder.seedAll()).thenReturn(report(Outcome.ABORTED), report(Outcome.INTERRUPTED));
        CatalogSeedRunner runner = new CatalogSeedRunner(seeder, true, exits::add);

        runner.run(new DefaultApplicationArguments());
        runner.run(new DefaultApplicationArguments());

        assertThat(exits).containsExactly(1, 1);
    }

    @Test
    void withAWebServerTheApplicationKeepsRunning() {
        when(seeder.seedAll()).thenReturn(report(Outcome.COMPLETED));

        new CatalogSeedRunner(seeder, false, exits::add).run(new DefaultApplicationArguments());

        verify(seeder).seedAll();
        assertThat(exits).isEmpty();
    }
}
