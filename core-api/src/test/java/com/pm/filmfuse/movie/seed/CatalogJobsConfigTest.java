package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.seed.CatalogRefreshProperties.Step;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Scheduling of the catalog freshness jobs: nightly top-up at 04:00 UTC and weekly refresh Sundays
 * 05:00 UTC by default, each behind its own {@code enabled} property, cron read from the effective
 * settings before every execution.
 */
class CatalogJobsConfigTest {

    /** Saturday. */
    private static final Instant SATURDAY_MIDNIGHT = Instant.parse("2026-10-17T00:00:00Z");

    private final CatalogTopUpJob topUp = mock(CatalogTopUpJob.class);
    private final CatalogRefreshJob refresh = mock(CatalogRefreshJob.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CatalogJobsConfig.class)
            .withBean(CatalogTopUpJob.class, () -> topUp)
            .withBean(CatalogRefreshJob.class, () -> refresh);

    private static List<TriggerTask> tasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(ScheduledTask::getTask).map(TriggerTask.class::cast).toList();
    }

    private static Instant next(TriggerTask task, Instant now) {
        return task.getTrigger().nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC)));
    }

    /** The task whose runnable calls the top-up (true) or the refresh (false). */
    private TriggerTask taskOf(List<TriggerTask> tasks, boolean topUpTask) {
        for (TriggerTask task : tasks) {
            reset(topUp, refresh);
            task.getRunnable().run();
            try {
                if (topUpTask) {
                    verify(topUp).scheduledRun();
                } else {
                    verify(refresh).scheduledRun();
                }
                return task;
            } catch (AssertionError notThisOne) {
                // try the next task
            }
        }
        throw new AssertionError("no such task");
    }

    @Test
    void bothJobsAreScheduledByDefaultTopUpNightlyAndRefreshOnSundayMorning() {
        runner.run(context -> {
            List<TriggerTask> tasks = tasks(context);
            assertThat(tasks).hasSize(2);
            assertThat(next(taskOf(tasks, true), SATURDAY_MIDNIGHT)).isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
            assertThat(next(taskOf(tasks, false), SATURDAY_MIDNIGHT))
                    .isEqualTo(Instant.parse("2026-10-18T05:00:00Z"));
            Settings settings = context.getBean(CatalogRefreshSettings.class).current();
            assertThat(settings.topUpPages()).isEqualTo(50);
            assertThat(settings.mainTierSize()).isEqualTo(20_000);
            assertThat(settings.steps()).containsExactlyInAnyOrder(Step.values());
        });
    }

    @Test
    void cronExpressionsAndSizesAreConfigurable() {
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.cron=0 15 2 * * *",
                        "filmfuse.catalog.seed.top-up.pages=7",
                        "filmfuse.catalog.refresh.cron=0 30 1 * * WED",
                        "filmfuse.catalog.refresh.main-tier-size=500",
                        "filmfuse.catalog.refresh.steps=main-tier,changes",
                        "filmfuse.catalog.refresh.refresh-after=P3D",
                        "filmfuse.catalog.refresh.changes-days=30",
                        "filmfuse.catalog.refresh.new-release-pages-per-year=3")
                .run(context -> {
                    List<TriggerTask> tasks = tasks(context);
                    assertThat(next(taskOf(tasks, true), SATURDAY_MIDNIGHT))
                            .isEqualTo(Instant.parse("2026-10-17T02:15:00Z"));
                    assertThat(next(taskOf(tasks, false), SATURDAY_MIDNIGHT))
                            .isEqualTo(Instant.parse("2026-10-21T01:30:00Z"));
                    Settings settings = context.getBean(CatalogRefreshSettings.class).current();
                    assertThat(settings.topUpPages()).isEqualTo(7);
                    assertThat(settings.mainTierSize()).isEqualTo(500);
                    assertThat(settings.steps()).containsExactlyInAnyOrder(Step.MAIN_TIER, Step.CHANGES);
                    assertThat(settings.refreshAfter()).isEqualTo(Duration.ofDays(3));
                    assertThat(settings.changesDays()).isEqualTo(14); // TMDB's maximum
                    assertThat(settings.newReleasePagesPerYear()).isEqualTo(3);
                });
    }

    @Test
    void eachJobHasItsOwnSwitch() {
        runner.withPropertyValues("filmfuse.catalog.refresh.enabled=false").run(context -> {
            List<TriggerTask> tasks = tasks(context);
            assertThat(tasks).hasSize(1);
            tasks.getFirst().getRunnable().run();
            verify(topUp).scheduledRun();
            verify(refresh, never()).scheduledRun();
        });
        reset(topUp, refresh);
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.enabled=false").run(context -> {
            List<TriggerTask> tasks = tasks(context);
            assertThat(tasks).hasSize(1);
            tasks.getFirst().getRunnable().run();
            verify(refresh).scheduledRun();
            verify(topUp, never()).scheduledRun();
        });
        runner.withPropertyValues("filmfuse.catalog.seed.top-up.enabled=false", "filmfuse.catalog.refresh.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
    }

    @Test
    void theCronIsReadFromTheEffectiveSettingsBeforeEveryExecutionAndABadOneFallsBackToTheDefault() {
        Settings base = Settings.of(new CatalogSeedProperties.TopUp(null, null, null), CatalogRefreshProperties.defaults());
        AtomicReference<String> refreshCron = new AtomicReference<>("0 0 6 * * *");
        CatalogRefreshSettings dynamic = () -> new Settings(true, base.topUpCron(), base.topUpPages(), true,
                refreshCron.get(), base.steps(), base.mainTierSize(), base.refreshAfter(), base.newReleasePagesPerYear(),
                base.newReleaseMinVotes(), base.rejectRecheckLimit(), base.changesDays(), base.changesMaxPages(),
                base.maxConsecutiveFailures());
        runner.withBean("runtimeSettings", CatalogRefreshSettings.class, () -> dynamic, bd -> bd.setPrimary(true))
                .run(context -> {
                    TriggerTask task = taskOf(tasks(context), false);
                    assertThat(next(task, SATURDAY_MIDNIGHT)).isEqualTo(Instant.parse("2026-10-17T06:00:00Z"));
                    refreshCron.set("0 0 7 * * *");
                    assertThat(next(task, SATURDAY_MIDNIGHT)).isEqualTo(Instant.parse("2026-10-17T07:00:00Z"));
                    refreshCron.set("not a cron");
                    assertThat(next(task, SATURDAY_MIDNIGHT)).isEqualTo(Instant.parse("2026-10-18T05:00:00Z"));
                });
    }

    @Test
    void theShippedPropertiesScheduleBothAndGiveTheSchedulerMoreThanOneThread() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .run(context -> {
                    assertThat(tasks(context)).hasSize(2);
                    // A long top-up or refresh must not block the other jobs (vector refresh every 15 s).
                    assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isGreaterThanOrEqualTo(2);
                    CatalogRefreshProperties p = context.getBean(CatalogRefreshProperties.class);
                    assertThat(p.cron()).isEqualTo("0 0 5 * * SUN");
                    assertThat(p.mainTierSize()).isEqualTo(20_000);
                    assertThat(p.refreshAfter()).isEqualTo(Duration.ofDays(6));
                    assertThat(p.newReleasePagesPerYear()).isEqualTo(20);
                    assertThat(p.changesDays()).isEqualTo(7);
                    assertThat(p.steps()).containsExactly(Step.MAIN_TIER, Step.NEW_RELEASES, Step.REJECTS,
                            Step.CHANGES);
                    assertThat(context.getBean(CatalogSeedProperties.class).topUp().pages()).isEqualTo(50);
                });
    }

    @Test
    void neitherJobIsScheduledUnderTheSeedOrTestProfile() {
        for (String profile : List.of("seed", "test")) {
            runner.withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class));
        }
    }
}
