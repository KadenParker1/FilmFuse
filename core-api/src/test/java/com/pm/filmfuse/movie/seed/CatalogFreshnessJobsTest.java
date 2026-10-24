package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.support.FakeCatalogJobLock;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CatalogTopUpJob, CatalogRefreshJob and the CatalogJobs facade: lock, runtime switch, history. */
class CatalogFreshnessJobsTest {

    private final CatalogSeedService seeder = mock(CatalogSeedService.class);
    private final CatalogRefreshService refresher = mock(CatalogRefreshService.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final JobRunRecorder recorder = new JobRunRecorder(runs, Clock.systemUTC(), 200);
    private final FakeCatalogJobLock lock = new FakeCatalogJobLock();
    private final AtomicReference<Settings> settings = new AtomicReference<>(
            Settings.of(new CatalogSeedProperties.TopUp(null, null, null), CatalogRefreshProperties.defaults()));

    private final CatalogTopUpJob topUp = new CatalogTopUpJob(seeder, settings::get, lock, recorder);
    private final CatalogRefreshJob refresh = new CatalogRefreshJob(refresher, settings::get, lock, recorder);
    private final CatalogJobs jobs = new CatalogJobs(topUp, refresh);

    private static SeedReport seedReport(Outcome outcome) {
        return new SeedReport(outcome, 50, 1000, 40, 960, 0, 0, Duration.ofSeconds(90));
    }

    private static RefreshReport refreshReport(Outcome outcome) {
        RefreshReport.Step step = new RefreshReport.Step(1, 2, 2, 1, 1, 0, 0, 0, 0);
        return new RefreshReport(outcome, null, step, step, step, step, Duration.ofMinutes(20));
    }

    private Settings withSwitches(boolean topUpEnabled, boolean refreshEnabled) {
        Settings s = settings.get();
        return new Settings(topUpEnabled, s.topUpCron(), s.topUpPages(), refreshEnabled, s.refreshCron(), s.steps(),
                s.mainTierSize(), s.refreshAfter(), s.newReleasePagesPerYear(), s.newReleaseMinVotes(),
                s.rejectRecheckLimit(), s.changesDays(), s.changesMaxPages(), s.maxConsecutiveFailures());
    }

    // --- top-up ------------------------------------------------------------------------------------------------

    @Test
    void theTopUpReadsItsPagesFromTheEffectiveSettingsAndIsRecorded() {
        when(seeder.topUp(50)).thenReturn(seedReport(Outcome.COMPLETED));
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(11L);

        SeedReport report = topUp.runNow("admin:42");

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(lock.acquired).isEqualTo(1);
        verify(runs).insert(eq(JobNames.CATALOG_TOP_UP), any(), isNull(), eq(JobOutcome.RUNNING), eq(Map.of()),
                eq("admin:42"));
        verify(runs).finish(eq(11L), any(), eq(JobOutcome.COMPLETED), eq(seedReport(Outcome.COMPLETED).toSummary()));
    }

    @Test
    void theTopUpSkipsWhileAnotherCatalogJobHoldsTheLock() {
        lock.held = true;

        SeedReport report = topUp.runNow(null);

        assertThat(report.outcome()).isEqualTo(Outcome.ALREADY_RUNNING);
        verify(seeder, never()).topUp(anyInt());
        verify(runs).insert(eq(JobNames.CATALOG_TOP_UP), any(), any(), eq(JobOutcome.SKIPPED),
                eq(Map.of("reason", "another catalog job is running")), isNull());
    }

    @Test
    void aTopUpAlreadyRunningInThisProcessIsRecordedAsSkipped() {
        when(seeder.topUp(anyInt())).thenReturn(SeedReport.notStarted());

        topUp.runNow(null);

        verify(runs).finish(anyLong(), any(), eq(JobOutcome.SKIPPED), any());
    }

    @Test
    void aPausedTopUpDoesNotRunOnScheduleButRunsOnRequest() {
        settings.set(withSwitches(false, true));
        when(seeder.topUp(anyInt())).thenReturn(seedReport(Outcome.COMPLETED));

        topUp.scheduledRun();
        verify(seeder, never()).topUp(anyInt());

        topUp.runNow("admin:1");
        verify(seeder).topUp(50);
    }

    @Test
    void aScheduledTopUpNeverThrows() {
        when(seeder.topUp(anyInt())).thenThrow(new IllegalStateException("boom"));

        topUp.scheduledRun();

        verify(runs).finish(anyLong(), any(), eq(JobOutcome.FAILED), eq(Map.of("error", "IllegalStateException: boom")));
    }

    // --- weekly refresh ----------------------------------------------------------------------------------------

    @Test
    void theRefreshRunsWithTheCurrentSettingsAndRecordsPerStepCounts() {
        when(refresher.refresh(settings.get())).thenReturn(refreshReport(Outcome.ABORTED));
        when(runs.insert(any(), any(), any(), any(), any(), any())).thenReturn(5L);

        RefreshReport report = refresh.runNow(null);

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        verify(runs).finish(eq(5L), any(), eq(JobOutcome.ABORTED), eq(refreshReport(Outcome.ABORTED).toSummary()));
    }

    @Test
    void theRefreshSkipsWhileAnotherCatalogJobHoldsTheLock() {
        lock.held = true;

        RefreshReport report = refresh.runNow("admin:7");

        assertThat(report.outcome()).isEqualTo(Outcome.ALREADY_RUNNING);
        verify(refresher, never()).refresh(any());
        verify(runs).insert(eq(JobNames.CATALOG_REFRESH), any(), any(), eq(JobOutcome.SKIPPED), any(), eq("admin:7"));
    }

    @Test
    void aPausedRefreshDoesNotRunOnSchedule() {
        settings.set(withSwitches(true, false));

        refresh.scheduledRun();

        verify(refresher, never()).refresh(any());
    }

    @Test
    void aScheduledRefreshRunsAndNeverThrows() {
        when(refresher.refresh(any())).thenReturn(refreshReport(Outcome.COMPLETED))
                .thenThrow(new IllegalStateException("boom"));

        refresh.scheduledRun();
        refresh.scheduledRun();

        verify(runs).finish(anyLong(), any(), eq(JobOutcome.COMPLETED), any());
        verify(runs).finish(anyLong(), any(), eq(JobOutcome.FAILED), any());
    }

    // --- facade ------------------------------------------------------------------------------------------------

    @Test
    void runNowDispatchesByJobName() {
        when(seeder.topUp(anyInt())).thenReturn(seedReport(Outcome.COMPLETED));
        when(refresher.refresh(any())).thenReturn(refreshReport(Outcome.COMPLETED));

        CatalogJobs.Result top = jobs.runNow(JobNames.CATALOG_TOP_UP, "admin:1");
        CatalogJobs.Result weekly = jobs.runNow(JobNames.CATALOG_REFRESH, "admin:1");

        assertThat(top.outcome()).isEqualTo(JobOutcome.COMPLETED);
        assertThat(top.summary()).containsEntry("fetched", 40);
        assertThat(weekly.outcome()).isEqualTo(JobOutcome.COMPLETED);
        assertThat(weekly.summary()).containsKey("mainTier");
    }

    @Test
    void runNowReportsSkippedWhenLockedAndRejectsUnknownJobs() {
        lock.held = true;

        assertThat(jobs.runNow(JobNames.CATALOG_REFRESH, "admin:1").outcome()).isEqualTo(JobOutcome.SKIPPED);
        assertThatThrownBy(() -> jobs.runNow("closeness", "admin:1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CatalogJobs.RUNNABLE).containsExactly(JobNames.CATALOG_TOP_UP, JobNames.CATALOG_REFRESH);
    }
}
