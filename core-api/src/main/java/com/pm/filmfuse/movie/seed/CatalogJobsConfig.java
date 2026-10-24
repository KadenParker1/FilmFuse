package com.pm.filmfuse.movie.seed;

import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The catalog freshness schedule: the nightly top-up ({@link CatalogTopUpJob}, scheduled when
 * {@code filmfuse.catalog.seed.top-up.enabled}) and the weekly refresh ({@link CatalogRefreshJob}, when
 * {@code filmfuse.catalog.refresh.enabled}); both default true and are false under the test and seed
 * profiles. Their cron expressions are read from {@link CatalogRefreshSettings} before every next
 * execution, so a runtime settings source can move them without a restart. Single replica: across
 * processes the {@code CatalogJobLock} keeps catalog jobs from overlapping, but two app replicas would
 * still each try every run.
 */
@Configuration
@EnableConfigurationProperties({CatalogRefreshProperties.class, CatalogSeedProperties.class})
public class CatalogJobsConfig {

    private static final Logger log = LoggerFactory.getLogger(CatalogJobsConfig.class);

    /**
     * The default settings source: the bound properties. A runtime source (an admin settings table)
     * replaces it by declaring a {@code @Primary} {@link CatalogRefreshSettings} bean.
     */
    @Bean
    CatalogRefreshSettings catalogRefreshSettings(CatalogSeedProperties seed, CatalogRefreshProperties refresh) {
        return new CatalogRefreshSettings.FromProperties(seed, refresh);
    }

    /**
     * A UTC cron trigger whose expression is read on every call; an invalid expression (e.g. a bad
     * runtime override) falls back to {@code fallback} instead of unscheduling the job.
     */
    static Trigger dynamicCron(String jobName, Supplier<String> cron, String fallback) {
        return context -> {
            String expression = cron.get();
            CronTrigger trigger;
            try {
                trigger = new CronTrigger(expression, ZoneOffset.UTC);
            } catch (IllegalArgumentException ex) {
                log.error("Invalid cron '{}' for {}; using the default '{}'", expression, jobName, fallback);
                trigger = new CronTrigger(fallback, ZoneOffset.UTC);
            }
            return trigger.nextExecution(context);
        };
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.catalog.seed.top-up.enabled", havingValue = "true", matchIfMissing = true)
    static class TopUpScheduling implements SchedulingConfigurer {

        private final CatalogTopUpJob job;
        private final CatalogRefreshSettings settings;

        TopUpScheduling(CatalogTopUpJob job, CatalogRefreshSettings settings) {
            this.job = job;
            this.settings = settings;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addTriggerTask(job::scheduledRun, dynamicCron("the catalog top-up",
                    () -> settings.current().topUpCron(), CatalogSeedProperties.DEFAULT_CRON));
        }
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.catalog.refresh.enabled", havingValue = "true", matchIfMissing = true)
    static class RefreshScheduling implements SchedulingConfigurer {

        private final CatalogRefreshJob job;
        private final CatalogRefreshSettings settings;

        RefreshScheduling(CatalogRefreshJob job, CatalogRefreshSettings settings) {
            this.job = job;
            this.settings = settings;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addTriggerTask(job::scheduledRun, dynamicCron("the catalog refresh",
                    () -> settings.current().refreshCron(), CatalogRefreshProperties.DEFAULT_CRON));
        }
    }
}
