package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogAuthenticationException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.ChangedIdsPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogRefreshProperties.Step;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CatalogRefreshService step by step, with mocked storage and catalog, a fake pacer clock and sleeper. */
class CatalogRefreshServiceTest {

    /** A Sunday: new releases are 2025 and 2026, the change window 2026-10-11..2026-10-18. */
    private static final Instant NOW = Instant.parse("2026-10-18T05:00:00Z");

    private final MovieCatalogClient catalog = mock(MovieCatalogClient.class);
    private final CatalogEnrichmentService store = mock(CatalogEnrichmentService.class);
    private final MovieEnrichmentRepository movies = mock(MovieEnrichmentRepository.class);
    private final CatalogSeedRejectRepository rejects = mock(CatalogSeedRejectRepository.class);
    private final AtomicLong databaseBytes = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private int paced;
    private final List<Duration> backoffs = new ArrayList<>();
    private Sleeper backoffSleeper = backoffs::add;

    @BeforeEach
    void nothingFreshNothingRejected() {
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of());
        when(movies.storedAmong(anyCollection())).thenReturn(Set.of());
        when(movies.topByPopularity(anyInt())).thenReturn(List.of());
        when(rejects.rejectedSince(anyCollection(), any())).thenReturn(Set.of());
        when(rejects.rejectedBefore(any(), anyInt())).thenReturn(List.of());
    }

    private static CatalogSeedProperties seedProperties(int maxRetries, int authFailureLimit, int maxDatabaseMb) {
        return new CatalogSeedProperties(null, null, null, 20d, maxRetries, Duration.ofSeconds(1), null,
                authFailureLimit, 1, null, null, maxDatabaseMb, null, null, null);
    }

    private CatalogRefreshService service(CatalogSeedProperties properties) {
        RequestPacer pacer = new RequestPacer(properties.requestsPerSecond(), () -> {
            paced++;
            return nanos.get();
        }, duration -> nanos.addAndGet(duration.toNanos()));
        return new CatalogRefreshService(catalog, store, movies, rejects, databaseBytes::get, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), pacer, duration -> backoffSleeper.sleep(duration));
    }

    private CatalogRefreshService service() {
        return service(seedProperties(0, 3, 0));
    }

    private static Settings settings(int mainTier, int pagesPerYear, int changesMaxPages, Step... steps) {
        return new Settings(true, "0 0 4 * * *", 50, true, "0 0 5 * * SUN",
                steps.length == 0 ? EnumSet.allOf(Step.class) : EnumSet.copyOf(Arrays.asList(steps)), mainTier,
                Duration.ofDays(6), pagesPerYear, 5, 100, 7, changesMaxPages, 25);
    }

    private static Settings only(Step... steps) {
        return settings(10, 2, 10, steps);
    }

    /** Passes the quality bar: poster, runtime, overview, 100 votes. */
    private static CatalogMovie good(int id) {
        return new CatalogMovie(id, "Movie " + id, 2026, 100, "/p" + id + ".jpg", null, "Overview", List.of(18), 10.0,
                7.0, "PG", List.of("friendship"), 100);
    }

    /** No poster: fails the list-level quality check. */
    private static CatalogMovie posterless(int id) {
        return new CatalogMovie(id, "Movie " + id, 2026, 100, null, null, "Overview", List.of(18), 10.0, 7.0, "PG",
                List.of(), 100);
    }

    /** Fails only after details: no runtime (list results never carry one). */
    private static CatalogMovie noRuntime(int id) {
        return new CatalogMovie(id, "Movie " + id, 2026, null, "/p.jpg", null, "Overview", List.of(18), 10.0, 7.0,
                null, List.of(), 100);
    }

    private static CatalogPage listPage(int page, int totalPages, CatalogMovie... movies) {
        return new CatalogPage(Arrays.stream(movies).map(CatalogMovie::asListResult).toList(), page, totalPages);
    }

    private static UpstreamUnavailableException outage() {
        return new UpstreamUnavailableException("down", new IllegalStateException("503"));
    }

    // --- main tier ---------------------------------------------------------------------------------------------

    @Test
    void theMainTierReFetchesTheTopStoredMoviesWithoutTheQualityBarAndSkipsFreshOnes() {
        when(movies.topByPopularity(3)).thenReturn(List.of(1, 2, 3));
        when(movies.checkedSince(eq(List.of(1, 2, 3)), any())).thenReturn(Set.of(2));
        when(catalog.details(1)).thenReturn(posterless(1));
        when(catalog.details(3)).thenThrow(new NotFoundException("gone"));

        RefreshReport report = service().refresh(settings(3, 2, 10, Step.MAIN_TIER));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.mainTier()).isEqualTo(new RefreshReport.Step(0, 3, 3, 1, 1, 1, 0, 0, 0));
        verify(store).storeDetails(posterless(1));
        verify(store).markChecked(3);
        verify(catalog, never()).details(2);
        verify(rejects, never()).record(anyInt(), any());
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(movies).checkedSince(eq(List.of(1, 2, 3)), since.capture());
        assertThat(since.getValue()).isEqualTo(NOW.minus(Duration.ofDays(6)));
        assertThat(paced).isEqualTo(2);
    }

    @Test
    void theMainTierIsWorkedInChunksWithTheSizeGuardBeforeEach() {
        List<Integer> tier = IntStream.rangeClosed(1, 450).boxed().toList();
        when(movies.topByPopularity(450)).thenReturn(tier);
        when(catalog.details(anyInt())).thenAnswer(invocation -> good(invocation.getArgument(0)));
        AtomicLong sizeChecks = new AtomicLong();
        CatalogRefreshService service = new CatalogRefreshService(catalog, store, movies, rejects, () -> {
            sizeChecks.incrementAndGet();
            return 0L;
        }, seedProperties(0, 3, 300), Clock.fixed(NOW, ZoneOffset.UTC),
                new RequestPacer(1000, nanos::get, duration -> { }), duration -> { });

        RefreshReport report = service.refresh(settings(450, 2, 10, Step.MAIN_TIER));

        assertThat(report.mainTier().fetched()).isEqualTo(450);
        assertThat(sizeChecks.get()).isEqualTo(3);
        verify(movies, times(3)).checkedSince(anyCollection(), any());
    }

    @Test
    void anOversizedDatabaseAbortsBeforeAnyRequestAndSkipsTheRemainingSteps() {
        when(movies.topByPopularity(10)).thenReturn(List.of(1, 2));
        databaseBytes.set(301L * 1024 * 1024);

        RefreshReport report = service(seedProperties(0, 3, 300)).refresh(only());

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.abortReason()).contains("max-database-mb=300");
        verifyNoInteractions(catalog);
        verifyNoInteractions(store);
    }

    // --- new releases ------------------------------------------------------------------------------------------

    @Test
    void newReleasesDiscoverThisYearAndLastYearAndPassTheQualityBar() {
        when(catalog.discoverByYear(2025, 5, 1)).thenReturn(listPage(1, 2, good(10), posterless(11)));
        when(catalog.discoverByYear(2025, 5, 2)).thenReturn(listPage(2, 2, noRuntime(12)));
        when(catalog.discoverByYear(2026, 5, 1)).thenReturn(listPage(1, 1, good(10), good(14), good(15)));
        when(rejects.rejectedSince(anyCollection(), any())).thenAnswer(invocation ->
                invocation.<java.util.Collection<Integer>>getArgument(0).contains(14) ? Set.of(14) : Set.of());
        when(catalog.details(10)).thenReturn(good(10));
        when(catalog.details(12)).thenReturn(noRuntime(12));
        when(catalog.details(15)).thenReturn(good(15));

        RefreshReport report = service().refresh(only(Step.NEW_RELEASES));

        // 10 (once: de-duplicated), 15 stored; 11 rejected from the list; 12 after details; 14 rejected earlier.
        assertThat(report.newReleases()).isEqualTo(new RefreshReport.Step(3, 6, 5, 2, 0, 0, 0, 2, 1));
        verify(store).storeDetails(good(10));
        verify(store).storeDetails(good(15));
        verify(catalog, never()).details(11);
        verify(catalog, never()).details(14);
        verify(rejects).record(11, NOW);
        verify(rejects).record(12, NOW);
        verify(store, never()).storeListResults(any());
        verify(catalog, never()).discoverByYear(eq(2024), anyInt(), anyInt());
    }

    @Test
    void newReleasesReadAtMostPagesPerYearAndSkipFreshMovies() {
        when(catalog.discoverByYear(anyInt(), eq(5), eq(1))).thenAnswer(invocation ->
                listPage(1, 50, good(invocation.<Integer>getArgument(0))));
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of(2025, 2026));

        RefreshReport report = service().refresh(settings(10, 1, 10, Step.NEW_RELEASES));

        assertThat(report.newReleases().pages()).isEqualTo(2);
        assertThat(report.newReleases().skippedFresh()).isEqualTo(2);
        verify(catalog, never()).discoverByYear(anyInt(), anyInt(), eq(2));
        verify(catalog, never()).details(anyInt());
    }

    @Test
    void aDiscoverPageThatKeepsFailingEndsThatYearOnlyAfterItsRetries() {
        when(catalog.discoverByYear(2025, 5, 1)).thenThrow(outage());
        when(catalog.discoverByYear(2026, 5, 1)).thenReturn(listPage(1, 1, good(20)));
        when(catalog.details(20)).thenReturn(good(20));

        RefreshReport report = service(seedProperties(2, 3, 0)).refresh(only(Step.NEW_RELEASES));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.newReleases().failed()).isEqualTo(1);
        assertThat(report.newReleases().fetched()).isEqualTo(1);
        assertThat(backoffs).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    // --- rejected re-check -------------------------------------------------------------------------------------

    @Test
    void expiredRejectsAreStoredWhenTheyPassNowReStampedWhenTheyStillFailAndForgottenWhenGone() {
        when(rejects.rejectedBefore(NOW.minus(Duration.ofDays(30)), 100)).thenReturn(List.of(20, 21, 22, 23));
        when(catalog.details(20)).thenReturn(good(20));
        when(catalog.details(21)).thenReturn(noRuntime(21));
        when(catalog.details(22)).thenThrow(new NotFoundException("gone"));
        when(catalog.details(23)).thenThrow(outage());

        RefreshReport report = service().refresh(only(Step.REJECTS));

        assertThat(report.rejects()).isEqualTo(new RefreshReport.Step(0, 4, 4, 1, 0, 1, 1, 1, 0));
        verify(store).storeDetails(good(20));
        verify(rejects).delete(20);
        verify(rejects).record(21, NOW);
        verify(rejects).delete(22);
        verify(rejects, never()).delete(23);
        verify(rejects, never()).record(eq(23), any());
        verify(store, never()).storeDetails(noRuntime(21));
    }

    // --- long-tail changes -------------------------------------------------------------------------------------

    @Test
    void changesArePagedAndRefreshStoredMoviesOutsideTheMainTier() {
        when(movies.topByPopularity(1)).thenReturn(List.of(1));
        when(catalog.details(anyInt())).thenAnswer(invocation -> good(invocation.getArgument(0)));
        LocalDate from = LocalDate.of(2026, 10, 11);
        LocalDate to = LocalDate.of(2026, 10, 18);
        when(catalog.changedMovieIds(from, to, 1)).thenReturn(new ChangedIdsPage(List.of(1, 30, 31, 32), 1, 2));
        when(catalog.changedMovieIds(from, to, 2)).thenReturn(new ChangedIdsPage(List.of(33, 30), 2, 2));
        when(movies.storedAmong(anyCollection())).thenAnswer(invocation -> {
            Set<Integer> stored = new java.util.HashSet<>(invocation.<java.util.Collection<Integer>>getArgument(0));
            stored.retainAll(Set.of(1, 30, 31, 33));
            return stored;
        });
        when(movies.checkedSince(eq(List.of(30, 31)), any())).thenReturn(Set.of(31));

        RefreshReport report = service().refresh(settings(1, 2, 10, Step.MAIN_TIER, Step.CHANGES));

        assertThat(report.mainTier().fetched()).isEqualTo(1);
        // Page 1: 1 is main tier, 32 not stored, 31 fresh, 30 refreshed; page 2: 33 refreshed, 30 seen already.
        assertThat(report.changes()).isEqualTo(new RefreshReport.Step(2, 6, 3, 2, 1, 0, 0, 0, 0));
        verify(catalog, times(1)).details(1);
        verify(catalog).details(30);
        verify(catalog).details(33);
        verify(catalog, never()).details(31);
        verify(catalog, never()).details(32);
        verify(rejects, never()).record(anyInt(), any());
    }

    @Test
    void changesStopAtMaxPagesAndWithoutTheMainTierStepEveryStoredChangeCounts() {
        when(catalog.changedMovieIds(any(), any(), eq(1))).thenReturn(new ChangedIdsPage(List.of(1, 2), 1, 9));
        when(movies.storedAmong(anyCollection())).thenReturn(Set.of(1, 2));
        when(catalog.details(anyInt())).thenAnswer(invocation -> good(invocation.getArgument(0)));

        RefreshReport report = service().refresh(settings(10, 2, 1, Step.CHANGES));

        assertThat(report.changes().pages()).isEqualTo(1);
        assertThat(report.changes().fetched()).isEqualTo(2);
        verify(catalog, never()).changedMovieIds(any(), any(), eq(2));
        verify(movies, never()).topByPopularity(anyInt());
    }

    @Test
    void aClientThatCannotListChangesAbortsTheRun() {
        when(catalog.changedMovieIds(any(), any(), anyInt())).thenThrow(new UnsupportedOperationException("no"));

        RefreshReport report = service().refresh(only(Step.CHANGES));

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.abortReason()).contains("cannot list changes");
    }

    @Test
    void aFailingChangePageEndsTheStepButNotTheRun() {
        when(catalog.changedMovieIds(any(), any(), anyInt())).thenThrow(outage());

        RefreshReport report = service().refresh(only(Step.CHANGES));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.changes().failed()).isEqualTo(1);
        assertThat(report.changes().pages()).isZero();
    }

    // --- run-wide rules ----------------------------------------------------------------------------------------

    @Test
    void anIdHandledByAnEarlierStepIsNotFetchedAgain() {
        when(movies.topByPopularity(10)).thenReturn(List.of(10));
        when(catalog.details(10)).thenReturn(good(10));
        when(catalog.discoverByYear(anyInt(), anyInt(), anyInt())).thenReturn(listPage(1, 1, good(10)));
        when(rejects.rejectedBefore(any(), anyInt())).thenReturn(List.of(10));
        when(catalog.changedMovieIds(any(), any(), anyInt())).thenReturn(new ChangedIdsPage(List.of(10), 1, 1));
        when(movies.storedAmong(anyCollection())).thenReturn(Set.of(10));

        RefreshReport report = service().refresh(only());

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        verify(catalog, times(1)).details(10);
        assertThat(report.newReleases().candidates() + report.rejects().candidates() + report.changes().candidates())
                .isZero();
    }

    @Test
    void repeatedTokenRejectionsAbortTheRunAndLaterStepsDoNotRun() {
        when(movies.topByPopularity(10)).thenReturn(List.of(1, 2, 3, 4));
        when(catalog.details(anyInt())).thenThrow(new CatalogAuthenticationException("401",
                new IllegalStateException("401")));

        RefreshReport report = service(seedProperties(5, 3, 0)).refresh(only());

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.abortReason()).contains("401");
        assertThat(paced).isEqualTo(3);
        verify(catalog, never()).discoverByYear(anyInt(), anyInt(), anyInt());
    }

    @Test
    void tooManyMoviesFailingInARowMeansTheCatalogIsDown() {
        when(movies.topByPopularity(10)).thenReturn(IntStream.rangeClosed(1, 10).boxed().toList());
        when(catalog.details(anyInt())).thenThrow(outage());
        Settings settings = new Settings(true, "c", 50, true, "c", EnumSet.of(Step.MAIN_TIER), 10, Duration.ofDays(6),
                2, 5, 100, 7, 10, 4);

        RefreshReport report = service().refresh(settings);

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.mainTier().failed()).isEqualTo(4);
        assertThat(report.abortReason()).contains("4 movies in a row");
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        when(movies.topByPopularity(10)).thenReturn(IntStream.rangeClosed(1, 6).boxed().toList());
        when(catalog.details(anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id % 2 == 0) {
                return good(id);
            }
            throw outage();
        });
        Settings settings = new Settings(true, "c", 50, true, "c", EnumSet.of(Step.MAIN_TIER), 10, Duration.ofDays(6),
                2, 5, 100, 7, 10, 2);

        RefreshReport report = service().refresh(settings);

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.mainTier().failed()).isEqualTo(3);
        assertThat(report.mainTier().fetched()).isEqualTo(3);
    }

    @Test
    void anInterruptStopsTheRun() {
        when(movies.topByPopularity(10)).thenReturn(List.of(1));
        when(catalog.details(1)).thenThrow(outage());
        backoffSleeper = duration -> {
            throw new InterruptedException();
        };

        RefreshReport report = service(seedProperties(3, 3, 0)).refresh(only());

        assertThat(report.outcome()).isEqualTo(Outcome.INTERRUPTED);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void oneRunAtATimePerProcess() {
        AtomicReference<RefreshReport> nested = new AtomicReference<>();
        CatalogRefreshService service = service();
        when(movies.topByPopularity(10)).thenAnswer(invocation -> {
            nested.set(service.refresh(only()));
            return List.of();
        });

        RefreshReport outer = service.refresh(only(Step.MAIN_TIER));

        assertThat(outer.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(nested.get().outcome()).isEqualTo(Outcome.ALREADY_RUNNING);
        assertThat(service.refresh(only(Step.MAIN_TIER)).outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void onlyTheConfiguredStepsRun() {
        RefreshReport report = service().refresh(only(Step.REJECTS));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        verify(movies, never()).topByPopularity(anyInt());
        verify(catalog, never()).discoverByYear(anyInt(), anyInt(), anyInt());
        verify(catalog, never()).changedMovieIds(any(), any(), anyInt());
        verify(rejects).rejectedBefore(any(), eq(100));
    }

    @Test
    void theReportSummaryHasEveryStep() {
        when(movies.topByPopularity(10)).thenReturn(List.of(1));
        when(catalog.details(1)).thenReturn(good(1));

        Map<String, Object> summary = service().refresh(only(Step.MAIN_TIER)).toSummary();

        assertThat(summary).containsKeys("outcome", "mainTier", "newReleases", "rejects", "changes", "durationSeconds");
        assertThat(summary.get("outcome")).isEqualTo("COMPLETED");
        assertThat(summary.get("mainTier")).isEqualTo(Map.of("pages", 0, "listed", 1, "candidates", 1, "fetched", 1,
                "skippedFresh", 0, "notFound", 0, "failed", 0, "rejectedQuality", 0, "skippedRejected", 0));
        assertThat(summary).doesNotContainKey("abortReason");
    }
}
