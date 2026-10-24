package com.pm.filmfuse.rating;

import com.pm.filmfuse.rating.service.DigestConfig;
import com.pm.filmfuse.rating.service.DigestJob;
import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** filmfuse.digest.*: a weekly cron task when enabled (the default), none when disabled. */
class DigestConfigTest {

    private final DigestJob job = mock(DigestJob.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DigestConfig.class)
            .withBean(DigestJob.class, () -> job)
            .withBean(JobRunRecorder.class, () -> new JobRunRecorder(runs, Clock.systemUTC(), 200));

    @Test
    void enabledByDefaultOnMondayMornings() {
        runner.run(context -> {
            var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);
            CronTask task = (CronTask) tasks.iterator().next().getTask();
            assertThat(task.getExpression()).isEqualTo("0 0 9 * * MON");
            task.getRunnable().run();
            verify(job).sendAll();
        });
    }

    @Test
    void cronIsConfigurableAndDisabledMeansNoTask() {
        runner.withPropertyValues("filmfuse.digest.cron=0 0 18 * * FRI").run(context ->
                assertThat(((CronTask) context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next()
                        .getTask()).getExpression()).isEqualTo("0 0 18 * * FRI"));
        runner.withPropertyValues("filmfuse.digest.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }

    @Test
    void everyScheduledRunIsRecordedInTheJobHistory() {
        when(job.sendAll()).thenReturn(4);
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(3L);
        runner.run(context -> {
            context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next().getTask().getRunnable()
                    .run();
            verify(runs).insert(eq(JobNames.WEEKLY_DIGEST), any(), isNull(), eq(JobOutcome.RUNNING), eq(Map.of()), isNull());
            verify(runs).finish(eq(3L), any(), eq(JobOutcome.COMPLETED), eq(Map.of("sent", 4)));
        });
    }

    @Test
    void aFailingRunIsRecordedAsFailed() {
        when(job.sendAll()).thenThrow(new IllegalStateException("db down"));
        runner.run(context -> {
            Runnable tick = context.getBean(ScheduledTaskHolder.class).getScheduledTasks().iterator().next().getTask()
                    .getRunnable();
            org.assertj.core.api.Assertions.assertThatThrownBy(tick::run).hasMessage("db down");
            verify(runs).finish(anyLong(), any(), eq(JobOutcome.FAILED),
                    eq(Map.of("error", "IllegalStateException: db down")));
        });
    }
}
