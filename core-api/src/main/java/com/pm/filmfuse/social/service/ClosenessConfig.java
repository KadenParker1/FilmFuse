package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Properties always; the daily tick only when {@code filmfuse.closeness.enabled} (default true).
 * Every scheduled run is recorded in {@code app.job_runs}.
 */
@Configuration
@EnableConfigurationProperties(ClosenessProperties.class)
public class ClosenessConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.closeness.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {

        private final ClosenessJob job;
        private final JobRunRecorder recorder;

        Scheduling(ClosenessJob job, JobRunRecorder recorder) {
            this.job = job;
            this.recorder = recorder;
        }

        @Scheduled(cron = "${filmfuse.closeness.cron:" + ClosenessProperties.DEFAULT_CRON + "}", zone = "UTC")
        void tick() {
            recorder.run(JobNames.CLOSENESS, null, job::recomputeAll,
                    count -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of("rowsUpdated", count)));
        }
    }
}
