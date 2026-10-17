package com.pm.filmfuse.social.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Properties always; the daily tick only when {@code filmfuse.closeness.enabled} (default true). */
@Configuration
@EnableConfigurationProperties(ClosenessProperties.class)
public class ClosenessConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.closeness.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {

        private final ClosenessJob job;

        Scheduling(ClosenessJob job) {
            this.job = job;
        }

        @Scheduled(cron = "${filmfuse.closeness.cron:" + ClosenessProperties.DEFAULT_CRON + "}", zone = "UTC")
        void tick() {
            job.recomputeAll();
        }
    }
}
