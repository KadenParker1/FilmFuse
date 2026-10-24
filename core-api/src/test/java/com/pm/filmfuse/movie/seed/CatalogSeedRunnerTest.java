package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.support.FakeCatalogJobLock;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogSeedRunnerTest {

    private final CatalogSeedService seeder = mock(CatalogSeedService.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final JobRunRecorder recorder = new JobRunRecorder(runs, Clock.systemUTC(), 200);
    private final FakeCatalogJobLock lock = new FakeCatalogJobLock();
    private final List<Integer> exits = new ArrayList<>();

    private static SeedReport report(Outcome outcome) {
        return new SeedReport(outcome, 1, 2, 2, 0, 0, 0, Duration.ZERO);
    }

    private CatalogSeedRunner runner(boolean exitAfterRun) {
        return new CatalogSeedRunner(seeder, lock, recorder, exitAfterRun, exits::add);
    }

    @Test
    void aCompletedRunExitsZeroAndIsRecordedAsACliRunUnderTheLock() {
        when(seeder.seedAll()).thenReturn(report(Outcome.COMPLETED));
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(7L);

        runner(true).run(new DefaultApplicationArguments());

        verify(seeder).seedAll();
        assertThat(exits).containsExactly(0);
        assertThat(lock.acquired).isEqualTo(1);
        verify(runs).insert(eq(JobNames.CATALOG_SEED), any(), isNull(), eq(JobOutcome.RUNNING), eq(Map.of()),
                eq("cli"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(runs).finish(eq(7L), any(), eq(JobOutcome.COMPLETED), summary.capture());
        assertThat(summary.getValue()).containsEntry("fetched", 2).containsEntry("outcome", "COMPLETED");
    }

    @Test
    void anAbortedOrInterruptedRunExitsOne() {
        when(seeder.seedAll()).thenReturn(report(Outcome.ABORTED), report(Outcome.INTERRUPTED));
        CatalogSeedRunner runner = runner(true);

        runner.run(new DefaultApplicationArguments());
        runner.run(new DefaultApplicationArguments());

        assertThat(exits).containsExactly(1, 1);
        verify(runs).finish(anyLong(), any(), eq(JobOutcome.ABORTED), any());
        verify(runs).finish(anyLong(), any(), eq(JobOutcome.INTERRUPTED), any());
    }

    @Test
    void anotherCatalogJobHoldingTheLockMeansNoSeedAndExitOne() {
        lock.held = true;

        runner(true).run(new DefaultApplicationArguments());

        verify(seeder, never()).seedAll();
        assertThat(exits).containsExactly(1);
        verify(runs).insert(eq(JobNames.CATALOG_SEED), any(), any(), eq(JobOutcome.SKIPPED), any(), eq("cli"));
    }

    @Test
    void withAWebServerTheApplicationKeepsRunning() {
        when(seeder.seedAll()).thenReturn(report(Outcome.COMPLETED));

        runner(false).run(new DefaultApplicationArguments());

        verify(seeder).seedAll();
        assertThat(exits).isEmpty();
    }

    @Test
    void aSeedThatThrowsIsRecordedAsFailedAndRethrown() {
        when(seeder.seedAll()).thenThrow(new IllegalStateException("boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner(true).run(new DefaultApplicationArguments()))
                .hasMessage("boom");

        verify(runs).finish(anyLong(), any(), eq(JobOutcome.FAILED),
                eq(Map.of("error", "IllegalStateException: boom")));
    }
}
