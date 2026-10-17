package com.pm.filmfuse.rating.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Properties always; the weekly tick only when {@code filmfuse.digest.enabled} (default true). */
@Configuration
@EnableConfigurationProperties(DigestProperties.class)
public class DigestConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.digest.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {

        private final DigestJob job;

        Scheduling(DigestJob job) {
            this.job = job;
        }

        @Scheduled(cron = "${filmfuse.digest.cron:" + DigestProperties.DEFAULT_CRON + "}", zone = "UTC")
        void tick() {
            job.sendAll();
        }
    }
}
