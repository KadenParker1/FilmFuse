package com.pm.filmfuse.common.job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JobRunRecorder over a mocked repository: lifecycle, pruning, and that recording never breaks a job. */
class JobRunRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final JobRunRecorder recorder = new JobRunRecorder(runs, Clock.fixed(NOW, ZoneOffset.UTC), 3);

    @Test
    void aRunIsInsertedAsRunningPrunedAndFinishedWithItsSummary() {
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(41L);

        String result = recorder.run("job", "admin:x", () -> "done",
                value -> new JobRunRecorder.Finish(JobOutcome.ABORTED, Map.of("value", value)));

        assertThat(result).isEqualTo("done");
        verify(runs).insert("job", NOW, null, JobOutcome.RUNNING, Map.of(), "admin:x");
        verify(runs).prune("job", 3);
        verify(runs).finish(41L, NOW, JobOutcome.ABORTED, Map.of("value", "done"));
    }

    @Test
    void anExceptionIsRecordedAsFailedAndRethrown() {
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> recorder.run("job", null, () -> {
            throw new IllegalStateException("catalog exploded");
        }, value -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of()))).hasMessage("catalog exploded");

        verify(runs).finish(5L, NOW, JobOutcome.FAILED, Map.of("error", "IllegalStateException: catalog exploded"));
    }

    @Test
    void aBrokenHistoryTableNeverStopsTheJob() {
        when(runs.insert(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        doThrow(new DataAccessResourceFailureException("down")).when(runs).finish(anyLong(), any(), any(), any());

        Integer result = recorder.run("job", null, () -> 7,
                value -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of()));

        assertThat(result).isEqualTo(7);
    }

    @Test
    void whenTheStartWasNotRecordedTheFinishInsertsTheWholeRun() {
        when(runs.insert(any(), any(), isNull(), eq(JobOutcome.RUNNING), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("blip"));

        recorder.run("job", "cli", () -> 1, value -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of("n", 1)));

        verify(runs).insert("job", NOW, NOW, JobOutcome.COMPLETED, Map.of("n", 1), "cli");
        verify(runs, never()).finish(anyLong(), any(), any(), any());
    }

    @Test
    void aDescribeThatThrowsStillFinishesTheRun() {
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(9L);

        recorder.run("job", null, () -> "x", value -> {
            throw new IllegalArgumentException("bad summary");
        });

        verify(runs).finish(9L, NOW, JobOutcome.COMPLETED, Map.of());
    }

    @Test
    void skippedAndOneShotRunsAreSingleRowsAndPruned() {
        recorder.recordSkipped("job", "admin:y", "another catalog job is running");
        recorder.recordFinished("other", null, NOW.minusSeconds(60), JobOutcome.FAILED, Map.of("failed", 2));

        verify(runs).insert("job", NOW, NOW, JobOutcome.SKIPPED, Map.of("reason", "another catalog job is running"),
                "admin:y");
        verify(runs).insert("other", NOW.minusSeconds(60), NOW, JobOutcome.FAILED, Map.of("failed", 2), null);
        verify(runs).prune("job", 3);
        verify(runs).prune("other", 3);
    }

    @Test
    void recordFinishedSwallowsDatabaseErrors() {
        when(runs.insert(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        recorder.recordFinished("job", null, NOW, JobOutcome.FAILED, null);

        verify(runs, never()).prune(anyString(), anyInt());
    }

    @Test
    void errorSummariesAreCut() {
        String longMessage = "x".repeat(1_000);

        Object error = JobRunRecorder.errorSummary(new IllegalStateException(longMessage)).get("error");

        assertThat((String) error).startsWith("IllegalStateException: xxx").endsWith("...").hasSize(303);
        assertThat(JobRunRecorder.errorSummary(new NullPointerException())).containsEntry("error",
                "NullPointerException");
    }

    @Test
    void keepIsAtLeastOne() {
        new JobRunRecorder(runs, Clock.fixed(NOW, ZoneOffset.UTC), 0).recordSkipped("job", null, "r");

        verify(runs).prune("job", 1);
    }
}
