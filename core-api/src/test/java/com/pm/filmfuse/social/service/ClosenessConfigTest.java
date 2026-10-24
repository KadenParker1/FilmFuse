package com.pm.filmfuse.social.service;

import java.util.Set;
import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** filmfuse.closeness.*: a daily cron task when enabled (the default), none at all when disabled. */
class ClosenessConfigTest {

    private final ClosenessJob job = mock(ClosenessJob.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClosenessConfig.class)
            .withBean(ClosenessJob.class, () -> job)
            .withBean(JobRunRecorder.class, () -> new JobRunRecorder(runs, Clock.systemUTC(), 200));

    private static Set<ScheduledTask> tasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
    }

    @Test
    void enabledByDefaultAsADailyCronTask() {
        runner.run(context -> {
            assertThat(tasks(context)).hasSize(1);
            CronTask task = (CronTask) tasks(context).iterator().next().getTask();
            assertThat(task.getExpression()).isEqualTo("0 30 3 * * *");
            task.getRunnable().run();
            verify(job, times(1)).recomputeAll();
            assertThat(context.getBean(ClosenessProperties.class).tasteWeight()).isEqualTo(0.25);
        });
    }

    @Test
    void cronAndWeightsAreConfigurable() {
        runner.withPropertyValues("filmfuse.closeness.cron=0 0 1 * * *", "filmfuse.closeness.base=0.3",
                        "filmfuse.closeness.co-session-weight=0.5", "filmfuse.closeness.co-session-saturation=3",
                        "filmfuse.closeness.taste-weight=0.2", "filmfuse.closeness.window-days=30",
                        "filmfuse.closeness.neutral-taste=0.4")
                .run(context -> {
                    assertThat(((CronTask) tasks(context).iterator().next().getTask()).getExpression())
                            .isEqualTo("0 0 1 * * *");
                    ClosenessProperties p = context.getBean(ClosenessProperties.class);
                    assertThat(p.base()).isEqualTo(0.3);
                    assertThat(p.coSessionWeight()).isEqualTo(0.5);
                    assertThat(p.coSessionSaturation()).isEqualTo(3);
                    assertThat(p.tasteWeight()).isEqualTo(0.2);
                    assertThat(p.windowDays()).isEqualTo(30);
                    assertThat(p.neutralTaste()).isEqualTo(0.4);
                });
    }

    @Test
    void disabledMeansNoScheduledTask() {
        runner.withPropertyValues("filmfuse.closeness.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }

    @Test
    void everyScheduledRunIsRecordedInTheJobHistory() {
        when(job.recomputeAll()).thenReturn(4);
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(3L);
        runner.run(context -> {
            context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next().getTask().getRunnable()
                    .run();
            verify(runs).insert(eq(JobNames.CLOSENESS), any(), isNull(), eq(JobOutcome.RUNNING), eq(Map.of()), isNull());
            verify(runs).finish(eq(3L), any(), eq(JobOutcome.COMPLETED), eq(Map.of("rowsUpdated", 4)));
        });
    }

    @Test
    void aFailingRunIsRecordedAsFailed() {
        when(job.recomputeAll()).thenThrow(new IllegalStateException("db down"));
        runner.run(context -> {
            Runnable tick = context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next().getTask()
                    .getRunnable();
            org.assertj.core.api.Assertions.assertThatThrownBy(tick::run).hasMessage("db down");
            verify(runs).finish(anyLong(), any(), eq(JobOutcome.FAILED),
                    eq(Map.of("error", "IllegalStateException: db down")));
        });
    }
}
