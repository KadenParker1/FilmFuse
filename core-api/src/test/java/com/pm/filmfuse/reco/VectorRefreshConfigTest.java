package com.pm.filmfuse.reco;

import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import com.pm.filmfuse.reco.service.VectorBuildService;
import com.pm.filmfuse.reco.service.VectorRefreshConfig;
import com.pm.filmfuse.reco.service.VectorRefreshProperties;
import com.pm.filmfuse.reco.service.VectorRefreshScheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * filmfuse.vectors.refresh.*: with scheduling enabled, VectorRefreshScheduler's tick is registered
 * as a fixed-delay task with the configured interval; disabled (as under the test profile) there is
 * no scheduling infrastructure and no task at all.
 */
class VectorRefreshConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VectorRefreshConfig.class)
            .withBean(UserVectorRepository.class, () -> mock(UserVectorRepository.class))
            .withBean(VectorBuildService.class, () -> mock(VectorBuildService.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JobRunRecorder.class, () -> mock(JobRunRecorder.class))
            .withBean(VectorRefreshScheduler.class);

    private static Set<ScheduledTask> tasks(org.springframework.context.ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
    }

    @Test
    void enabledRegistersTheTickWithTheConfiguredDelay() {
        runner.withPropertyValues("filmfuse.vectors.refresh.enabled=true", "filmfuse.vectors.refresh.interval=PT42S")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScheduledTaskHolder.class);
                    assertThat(tasks(context)).hasSize(1);
                    FixedDelayTask task = (FixedDelayTask) tasks(context).iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(42));
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(42));
                    assertThat(task.toString()).contains("VectorRefreshScheduler.tick");
                });
    }

    @Test
    void enabledByDefaultWithTheDocumentedTimings() {
        runner.run(context -> {
            FixedDelayTask task = (FixedDelayTask) tasks(context).iterator().next().getTask();
            assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(15));
            VectorRefreshProperties properties = context.getBean(VectorRefreshProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.debounce()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.batchSize()).isEqualTo(50);
        });
    }

    @Test
    void disabledMeansNoScheduledTask() {
        runner.withPropertyValues("filmfuse.vectors.refresh.enabled=false", "filmfuse.vectors.refresh.debounce=PT30S")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
                    assertThat(context).hasSingleBean(VectorRefreshScheduler.class);
                    assertThat(context.getBean(VectorRefreshProperties.class).debounce()).isEqualTo(Duration.ofSeconds(30));
                });
    }
}
