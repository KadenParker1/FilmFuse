package com.pm.filmfuse.rating;

import com.pm.filmfuse.rating.service.DigestConfig;
import com.pm.filmfuse.rating.service.DigestJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** filmfuse.digest.*: a weekly cron task when enabled (the default), none when disabled. */
class DigestConfigTest {

    private final DigestJob job = mock(DigestJob.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DigestConfig.class)
            .withBean(DigestJob.class, () -> job);

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
}
