package com.pm.filmfuse.rating.service;

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
 * Properties always; the weekly tick only when {@code filmfuse.digest.enabled} (default true).
 * Every scheduled run is recorded in {@code app.job_runs}.
 */
@Configuration
@EnableConfigurationProperties(DigestProperties.class)
public class DigestConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.digest.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {

        private final DigestJob job;
        private final JobRunRecorder recorder;

        Scheduling(DigestJob job, JobRunRecorder recorder) {
            this.job = job;
            this.recorder = recorder;
        }

        @Scheduled(cron = "${filmfuse.digest.cron:" + DigestProperties.DEFAULT_CRON + "}", zone = "UTC")
        void tick() {
            recorder.run(JobNames.WEEKLY_DIGEST, null, job::sendAll,
                    count -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of("sent", count)));
        }
    }
}
