package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
}
