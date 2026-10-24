package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Another job's {@code @EnableScheduling} (closeness, digest, enrichment) also schedules the vector
 * tick, so the tick itself honours {@code filmfuse.vectors.refresh.enabled}.
 */
class VectorRefreshSchedulerTickTest {

    private final UserVectorRepository vectors = mock(UserVectorRepository.class);

    @Test
    void aDisabledTickDoesNothing() {
        new VectorRefreshScheduler(vectors, mock(VectorBuildService.class),
                new VectorRefreshProperties(false, null, null, null), Clock.systemUTC()).tick();
        verify(vectors, never()).findDue(any(), anyInt());
    }

    @Test
    void anEnabledTickRefreshes() {
        when(vectors.findDue(any(), anyInt())).thenReturn(List.of());
        new VectorRefreshScheduler(vectors, mock(VectorBuildService.class),
                new VectorRefreshProperties(true, null, null, null), Clock.systemUTC()).tick();
        verify(vectors).findDue(any(), anyInt());
    }

    private final VectorBuildService builder = mock(VectorBuildService.class);
    private final JobRunRecorder recorder = mock(JobRunRecorder.class);

    private VectorRefreshScheduler recording() {
        return new VectorRefreshScheduler(vectors, builder, new VectorRefreshProperties(true, null, null, null),
                Clock.systemUTC(), recorder);
    }

    @Test
    void aCleanPassIsNotRecorded() {
        UUID user = UUID.randomUUID();
        when(vectors.findDue(any(), anyInt())).thenReturn(List.of(user));
        when(builder.rebuildIfDirty(user)).thenReturn(Optional.empty());

        recording().tick();

        verifyNoInteractions(recorder);
    }

    @Test
    void aPassWithFailedUsersIsRecordedAsFailed() {
        UUID ok = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(vectors.findDue(any(), anyInt())).thenReturn(List.of(ok, broken));
        when(builder.rebuildIfDirty(ok)).thenReturn(Optional.empty());
        when(builder.rebuildIfDirty(broken)).thenThrow(new IllegalStateException("ml down"));

        recording().tick();

        verify(recorder).recordFinished(eq(JobNames.VECTOR_REFRESH), isNull(), any(), eq(JobOutcome.FAILED),
                eq(Map.of("due", 2, "built", 0, "failed", 1)));
    }

    @Test
    void aPassThatThrowsIsRecordedAndSwallowed() {
        when(vectors.findDue(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        recording().tick();

        verify(recorder).recordFinished(eq(JobNames.VECTOR_REFRESH), isNull(), any(), eq(JobOutcome.FAILED),
                eq(Map.of("error", "IllegalStateException: db down")));
    }
}
