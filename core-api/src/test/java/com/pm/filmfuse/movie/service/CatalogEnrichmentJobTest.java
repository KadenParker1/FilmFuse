package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.support.FakeCatalogJobLock;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * filmfuse.catalog.enrichment.*: every 10 minutes, 50 movies, when enabled; nothing when disabled.
 * Each run takes the catalog lock (skips while another catalog job holds it) and records runs that
 * did work or failed.
 */
class CatalogEnrichmentJobTest {

    private final CatalogEnrichmentService service = mock(CatalogEnrichmentService.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final FakeCatalogJobLock lock = new FakeCatalogJobLock();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogEnrichmentJob.class)
            .withBean(CatalogEnrichmentService.class, () -> service)
            .withBean(CatalogJobLock.class, () -> lock)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JobRunRecorder.class, () -> new JobRunRecorder(runs, Clock.systemUTC(), 200));

    private static FixedDelayTask task(ApplicationContext context) {
        return (FixedDelayTask) context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next()
                .getTask();
    }

    @Test
    void enabledByDefaultEveryTenMinutesWithFiftyMoviesAndAnIdleRunIsNotRecorded() {
        runner.run(context -> {
            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
            FixedDelayTask task = task(context);
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
            task.getRunnable().run();
            verify(service).enrich(50);
            assertThat(lock.acquired).isEqualTo(1);
            verifyNoInteractions(runs);
            CatalogEnrichmentProperties properties = context.getBean(CatalogEnrichmentProperties.class);
            assertThat(properties.sparseBelow()).isEqualTo(100);
        });
    }

    @Test
    void aRunThatCheckedMoviesIsRecorded() {
        when(service.enrich(anyInt())).thenReturn(12);
        runner.run(context -> {
            task(context).getRunnable().run();
            verify(runs).insert(eq(JobNames.CATALOG_ENRICHMENT), any(), any(), eq(JobOutcome.COMPLETED),
                    eq(Map.of("checked", 12)), isNull());
        });
    }

    @Test
    void intervalAndBatchAreConfigurableAndAFailureIsSwallowedAndRecorded() {
        when(service.enrich(anyInt())).thenThrow(new IllegalStateException("boom"));
        runner.withPropertyValues("filmfuse.catalog.enrichment.interval=PT1M", "filmfuse.catalog.enrichment.batch-size=7")
                .run(context -> {
                    FixedDelayTask task = task(context);
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
                    task.getRunnable().run();
                    verify(service).enrich(7);
                    verify(runs).insert(eq(JobNames.CATALOG_ENRICHMENT), any(), any(), eq(JobOutcome.FAILED),
                            eq(Map.of("error", "IllegalStateException: boom")), isNull());
                });
    }

    @Test
    void whileAnotherCatalogJobHoldsTheLockTheRunSkipsQuietly() {
        lock.held = true;
        runner.run(context -> {
            task(context).getRunnable().run();
            verify(service, never()).enrich(anyInt());
            verifyNoInteractions(runs);
        });
    }

    @Test
    void disabledMeansNoScheduledTask() {
        runner.withPropertyValues("filmfuse.catalog.enrichment.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }
}
