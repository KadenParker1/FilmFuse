package com.pm.filmfuse.movie.service;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** filmfuse.catalog.enrichment.*: every 10 minutes, 50 movies, when enabled; nothing when disabled. */
class CatalogEnrichmentJobTest {

    private final CatalogEnrichmentService service = mock(CatalogEnrichmentService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogEnrichmentJob.class)
            .withBean(CatalogEnrichmentService.class, () -> service);

    @Test
    void enabledByDefaultEveryTenMinutesWithFiftyMovies() {
        runner.run(context -> {
            var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
            task.getRunnable().run();
            verify(service).enrich(50);
            CatalogEnrichmentProperties properties = context.getBean(CatalogEnrichmentProperties.class);
            assertThat(properties.sparseBelow()).isEqualTo(100);
        });
    }

    @Test
    void intervalAndBatchAreConfigurableAndAFailureIsSwallowed() {
        when(service.enrich(anyInt())).thenThrow(new IllegalStateException("boom"));
        runner.withPropertyValues("filmfuse.catalog.enrichment.interval=PT1M", "filmfuse.catalog.enrichment.batch-size=7")
                .run(context -> {
                    FixedDelayTask task = (FixedDelayTask) context.getBean(ScheduledTaskHolder.class)
                            .getScheduledTasks().iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
                    task.getRunnable().run();
                    verify(service).enrich(7);
                });
    }

    @Test
    void disabledMeansNoScheduledTask() {
        runner.withPropertyValues("filmfuse.catalog.enrichment.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }
}
