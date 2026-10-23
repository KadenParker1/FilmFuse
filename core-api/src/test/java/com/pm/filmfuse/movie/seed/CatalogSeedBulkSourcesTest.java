package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Discover;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Quality;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Source;
import com.pm.filmfuse.movie.seed.CatalogSeedService.SeedPlan;
import com.pm.filmfuse.movie.seed.CatalogSeedStateRepository.Cursor;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The bulk sources (export, discover), the quality bar, the database size guard and source selection. */
class CatalogSeedBulkSourcesTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final long MB = 1024L * 1024;

    private final CatalogEnrichmentService store = mock(CatalogEnrichmentService.class);
    private final MovieEnrichmentRepository movies = mock(MovieEnrichmentRepository.class);
    private final CatalogSeedStateRepository state = mock(CatalogSeedStateRepository.class);
    private final CatalogSeedRejectRepository rejects = mock(CatalogSeedRejectRepository.class);
    private final TmdbIdExport export = mock(TmdbIdExport.class);
    private final MovieCatalogClient catalog = mock(MovieCatalogClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicLong databaseBytes = new AtomicLong(10 * MB);
    private DatabaseSizeProbe probe = databaseBytes::get;

    @BeforeEach
    void defaults() {
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of());
        when(rejects.rejectedSince(anyCollection(), any())).thenReturn(Set.of());
        when(state.nextPage(anyString())).thenReturn(Optional.empty());
        when(state.cursor(anyString())).thenReturn(Optional.empty());
        when(catalog.details(anyInt())).thenAnswer(inv -> good(inv.getArgument(0)));
    }

    private static CatalogSeedProperties properties(List<Source> sources, Integer maxDatabaseMb, Discover discover,
                                                    Quality quality) {
        return new CatalogSeedProperties(null, null, null, 1000d, 1, Duration.ZERO, null, 3, 1, null, sources,
                maxDatabaseMb, null, discover, quality);
    }

    private static CatalogSeedProperties properties() {
        return properties(null, null, null, null);
    }

    private CatalogSeedService service(CatalogSeedProperties properties) {
        return new CatalogSeedService(catalog, store, movies, state, rejects, export, () -> probe.currentBytes(),
                properties, clock, new RequestPacer(1000, System::nanoTime, duration -> { }), duration -> { });
    }

    /** A movie that passes the default quality bar. */
    private static CatalogMovie good(int id) {
        return new CatalogMovie(id, "Movie " + id, 2020, 100, "/p" + id + ".jpg", null, "Overview " + id,
                List.of(18), 1.0, 7.0, "PG", List.of("friendship"), 50);
    }

    private static CatalogMovie with(CatalogMovie m, Integer runtime, String poster, String overview, Integer votes) {
        return new CatalogMovie(m.id(), m.title(), m.releaseYear(), runtime, poster, m.backdropPath(), overview,
                m.genreIds(), m.popularity(), m.voteAverage(), m.certification(), m.keywords(), votes);
    }

    private static TmdbIdExport.Ids exportOf(LocalDate date, int count) {
        return new TmdbIdExport.Ids(date, IntStream.rangeClosed(1, count).toArray(), count, 0, 0, false);
    }

    private static CatalogPage listPage(int page, int totalPages, int... ids) {
        return new CatalogPage(IntStream.of(ids).mapToObj(id -> good(id).asListResult()).toList(), page, totalPages);
    }

    private static SeedPlan exportOnly() {
        return new SeedPlan(0, List.of(), true, true, false);
    }

    private static SeedPlan discoverOnly() {
        return new SeedPlan(0, List.of(), true, false, true);
    }

    // --- export ---

    @Test
    void exportIdsAreSeededInChunksWithAVersionedCursorThatIsClearedAtTheEnd() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 450));

        SeedReport report = service(properties()).seed(exportOnly());

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.candidates()).isEqualTo(450);
        assertThat(report.fetched()).isEqualTo(450);
        assertThat(report.pages()).isZero();
        InOrder order = inOrder(state);
        order.verify(state).save("export", 201, "2026-10-17");
        order.verify(state).save("export", 401, "2026-10-17");
        order.verify(state).save("export", 451, "2026-10-17");
        order.verify(state).clear("export");
        verify(state, never()).saveNextPage(eq("popular"), anyInt());
        verify(state, never()).clear("popular");
        // Ids are worked through in the export's (popularity) order.
        InOrder details = inOrder(catalog);
        details.verify(catalog).details(1);
        details.verify(catalog).details(450);
        // Filtered sources never cache list results.
        verify(store, never()).storeListResults(any());
    }

    @Test
    void anExportRunResumesAtTheCursorOfTheSameExportDate() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 450));
        when(state.cursor("export")).thenReturn(Optional.of(new Cursor(401, "2026-10-17")));

        SeedReport report = service(properties()).seed(exportOnly());

        assertThat(report.candidates()).isEqualTo(50);
        verify(catalog, never()).details(400);
        verify(catalog).details(401);
        verify(state).save("export", 451, "2026-10-17");
        verify(state).clear("export");
    }

    @Test
    void aCursorFromAnotherExportDateIsDroppedAndTheListStartsOver() throws Exception {
        // Yesterday's file was the newest one available (today's is not out yet).
        LocalDate yesterday = TODAY.minusDays(1);
        when(export.load(TODAY)).thenReturn(exportOf(yesterday, 10));
        when(state.cursor("export")).thenReturn(Optional.of(new Cursor(5, "2026-10-15")));
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of(1, 2, 3));

        SeedReport report = service(properties()).seed(exportOnly());

        assertThat(report.candidates()).isEqualTo(10);
        assertThat(report.skippedAlreadySeeded()).isEqualTo(3);
        assertThat(report.fetched()).isEqualTo(7);
        verify(catalog, never()).details(1);
        verify(state).save("export", 11, "2026-10-16");
    }

    @Test
    void anExportThatCannotBeReadAbortsAndKeepsTheCursor() throws Exception {
        when(export.load(TODAY)).thenThrow(new TmdbIdExport.ExportUnavailableException("no file"));
        assertThat(service(properties()).seed(exportOnly()).outcome()).isEqualTo(Outcome.ABORTED);

        doThrow(new IOException("truncated gzip")).when(export).load(TODAY);
        assertThat(service(properties()).seed(exportOnly()).outcome()).isEqualTo(Outcome.ABORTED);

        verify(state, never()).clear(anyString());
        verify(catalog, never()).details(anyInt());
    }

    @Test
    void idsAlreadySeenFromPopularPagesAreNotFetchedAgainFromTheExport() throws Exception {
        when(catalog.popular(null, 1)).thenReturn(listPage(1, 1, 1, 2));
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 3));

        SeedReport report = service(properties()).seed(new SeedPlan(5, List.of(), true, true, false));

        assertThat(report.candidates()).isEqualTo(3);
        verify(catalog, times(1)).details(1);
        verify(catalog, times(1)).details(3);
        // Each source has its own cursor row.
        verify(state).saveNextPage("popular", 2);
        verify(state).clear("popular");
        verify(state).save("export", 4, "2026-10-17");
        verify(state).clear("export");
    }

    // --- quality ---

    @Test
    void moviesFailingTheQualityBarAreNotStoredButRecordedAndCounted() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 5));
        when(catalog.details(2)).thenReturn(with(good(2), 100, null, "x", 50));
        when(catalog.details(3)).thenReturn(with(good(3), null, "/p.jpg", "x", 50));
        when(catalog.details(4)).thenReturn(with(good(4), 100, "/p.jpg", null, 50));
        when(catalog.details(5)).thenReturn(with(good(5), 100, "/p.jpg", "x", 4));

        SeedReport report = service(properties()).seed(exportOnly());

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.rejectedQuality()).isEqualTo(4);
        verify(store, times(1)).storeDetails(any());
        verify(store).storeDetails(good(1));
        for (int id = 2; id <= 5; id++) {
            verify(rejects).record(id, NOW);
        }
        // A rejected movie's existing row (if any) is left alone: not even stamped checked.
        verify(store, never()).markChecked(anyInt());
        verify(rejects).pruneBefore(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void idsRejectedWithinTheRejectTtlAreNotFetchedAgain() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 3));
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of(1));
        when(rejects.rejectedSince(anyCollection(), any())).thenReturn(Set.of(2));

        SeedReport report = service(properties()).seed(exportOnly());

        verify(rejects).rejectedSince(List.of(2, 3), NOW.minus(Duration.ofDays(30)));
        assertThat(report.skippedAlreadySeeded()).isEqualTo(1);
        assertThat(report.skippedRejected()).isEqualTo(1);
        assertThat(report.fetched()).isEqualTo(1);
        verify(catalog, never()).details(2);
    }

    @Test
    void theQualityBarIsConfigurable() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 2));
        when(catalog.details(1)).thenReturn(with(good(1), null, "/p.jpg", "x", 0));
        when(catalog.details(2)).thenReturn(with(good(2), 100, null, null, null));

        Quality lenient = new Quality(true, null, false, false, false, 0, null);
        SeedReport report = service(properties(null, null, null, lenient)).seed(exportOnly());

        assertThat(report.fetched()).isEqualTo(2);
        assertThat(report.rejectedQuality()).isZero();
    }

    @Test
    void aDisabledQualityBarStoresEverythingAndNeverTouchesTheRejects() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 2));
        when(catalog.details(anyInt())).thenAnswer(inv -> with(good(inv.getArgument(0)), null, null, null, 0));

        Quality off = new Quality(false, null, null, null, null, null, null);
        SeedReport report = service(properties(null, null, null, off)).seed(exportOnly());

        assertThat(report.fetched()).isEqualTo(2);
        verifyNoInteractions(rejects);
    }

    @Test
    void popularPagesAreNotFilteredByDefaultButCanBe() {
        CatalogMovie posterless = with(good(1), 100, null, "x", 50);
        when(catalog.popular(null, 1)).thenReturn(new CatalogPage(List.of(posterless.asListResult()), 1, 1));
        when(catalog.details(1)).thenReturn(posterless);

        SeedReport unfiltered = service(properties()).seed(new SeedPlan(1, List.of(), false));
        assertThat(unfiltered.fetched()).isEqualTo(1);
        verify(store).storeListResults(any());
        verifyNoInteractions(rejects);

        Quality forPopular = new Quality(true, List.of(Source.POPULAR), null, null, null, null, null);
        SeedReport filtered = service(properties(null, null, null, forPopular)).seed(new SeedPlan(1, List.of(), false));
        assertThat(filtered.fetched()).isZero();
        assertThat(filtered.rejectedQuality()).isEqualTo(1);
        // The posterless list result was enough to reject it: no second details request, no list upsert.
        verify(catalog, times(1)).details(1);
        verify(store, times(1)).storeListResults(any());
    }

    // --- discover ---

    @Test
    void discoverWalksEveryYearUpToPagesPerYearAndStopsAtAYearsLastPage() {
        when(catalog.discoverByYear(anyInt(), anyInt(), anyInt())).thenReturn(listPage(1, 1));
        when(catalog.discoverByYear(2024, 25, 1)).thenReturn(listPage(1, 9, 1, 2));
        when(catalog.discoverByYear(2024, 25, 2)).thenReturn(listPage(2, 9, 3));
        when(catalog.discoverByYear(2025, 25, 1)).thenReturn(listPage(1, 1, 4));
        Discover discover = new Discover(2024, null, 2, 25);

        SeedReport report = service(properties(null, null, discover, null)).seed(discoverOnly());

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        // to-year defaults to the current year: 2024 (2 pages), 2025 (1 of 1), 2026 (empty).
        assertThat(report.pages()).isEqualTo(4);
        assertThat(report.fetched()).isEqualTo(4);
        verify(catalog, never()).discoverByYear(2024, 25, 3);
        verify(catalog, never()).discoverByYear(2025, 25, 2);
        verify(catalog).discoverByYear(2026, 25, 1);
        verify(catalog, never()).discoverByYear(eq(2027), anyInt(), anyInt());
        String version = "2024..2026/2/25";
        InOrder order = inOrder(state);
        order.verify(state).save("discover", 2024_002, version);
        order.verify(state).save("discover", 2025_001, version);
        order.verify(state).save("discover", 2026_001, version);
        order.verify(state).save("discover", 2027_001, version);
        order.verify(state).clear("discover");
        verify(store, never()).storeListResults(any());
    }

    @Test
    void discoverListResultsThatAlreadyFailTheBarAreRejectedWithoutADetailsRequest() {
        CatalogMovie noVotes = with(good(1), null, "/p.jpg", "x", 2);
        CatalogMovie noPoster = with(good(2), null, null, "x", 90);
        when(catalog.discoverByYear(2026, 10, 1)).thenReturn(new CatalogPage(List.of(noVotes, noPoster,
                good(3).asListResult()), 1, 1));

        SeedReport report = service(properties(null, null, new Discover(2026, 2026, 5, null), null))
                .seed(discoverOnly());

        assertThat(report.rejectedQuality()).isEqualTo(2);
        assertThat(report.fetched()).isEqualTo(1);
        verify(catalog, never()).details(1);
        verify(catalog, never()).details(2);
        verify(rejects).record(1, NOW);
        verify(rejects).record(2, NOW);
    }

    @Test
    void discoverResumesAtTheStoredYearAndPageUnlessTheSettingsChanged() {
        when(catalog.discoverByYear(anyInt(), anyInt(), anyInt())).thenReturn(listPage(1, 1));
        Discover discover = new Discover(2000, 2010, 3, 10);
        when(state.cursor("discover")).thenReturn(Optional.of(new Cursor(2009_002, "2000..2010/3/10")));

        SeedReport resumed = service(properties(null, null, discover, null)).seed(discoverOnly());

        assertThat(resumed.pages()).isEqualTo(2);
        verify(catalog).discoverByYear(2009, 10, 2);
        verify(catalog).discoverByYear(2010, 10, 1);
        verify(catalog, never()).discoverByYear(2009, 10, 1);
        verify(catalog, never()).discoverByYear(2000, 10, 1);

        when(state.cursor("discover")).thenReturn(Optional.of(new Cursor(2009_002, "2000..2010/5/10")));
        SeedReport restarted = service(properties(null, null, discover, null)).seed(discoverOnly());
        assertThat(restarted.pages()).isEqualTo(11);
    }

    @Test
    void aDiscoverPageThatKeepsFailingAbortsAndKeepsTheCursor() {
        when(catalog.discoverByYear(2020, 10, 1)).thenReturn(listPage(1, 5, 1));
        when(catalog.discoverByYear(2020, 10, 2)).thenThrow(new UpstreamUnavailableException("down"));

        SeedReport report = service(properties(null, null, new Discover(2020, 2021, 5, 10), null))
                .seed(discoverOnly());

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        verify(state).save("discover", 2020_002, "2020..2021/5/10");
        verify(state, never()).clear(anyString());
        verify(catalog, never()).discoverByYear(eq(2021), anyInt(), anyInt());
    }

    @Test
    void aCatalogThatCannotDiscoverAbortsCleanly() {
        MovieCatalogClient basic = new MovieCatalogClient() {
            @Override
            public CatalogPage search(String query, int page) {
                return listPage(1, 1);
            }

            @Override
            public CatalogPage popular(Integer genreId, int page) {
                return listPage(1, 1);
            }

            @Override
            public CatalogMovie details(int id) {
                return good(id);
            }
        };
        CatalogSeedService service = new CatalogSeedService(basic, store, movies, state, rejects, export, () -> 0L,
                properties(), clock, new RequestPacer(1000, System::nanoTime, d -> { }), d -> { });

        assertThat(service.seed(discoverOnly()).outcome()).isEqualTo(Outcome.ABORTED);
    }

    // --- database size guard ---

    @Test
    void aDatabaseOverTheLimitStopsTheRunBeforeAnyRequest() {
        databaseBytes.set(451 * MB);

        SeedReport report = service(properties()).seed(new SeedPlan(5, List.of(1, 2), true, true, true));

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        verifyNoInteractions(catalog, export);
        verify(state, never()).clear(anyString());
    }

    @Test
    void theGuardStopsMidRunKeepingTheCursorAndTheNextRunResumesThere() {
        List<Integer> pagesRequested = new ArrayList<>();
        when(catalog.popular(eq(null), anyInt())).thenAnswer(inv -> {
            int page = inv.getArgument(1);
            pagesRequested.add(page);
            return listPage(page, 9, page);
        });
        // Page 2's details push the database over 300 MB.
        when(catalog.details(2)).thenAnswer(inv -> {
            databaseBytes.set(301 * MB);
            return good(2);
        });
        CatalogSeedProperties limited = properties(null, 300, null, null);

        SeedReport stopped = service(limited).seed(new SeedPlan(9, List.of(), true));

        assertThat(stopped.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(pagesRequested).containsExactly(1, 2);
        verify(state).saveNextPage("popular", 3);
        verify(state, never()).clear(anyString());

        // After an upgrade (a higher limit), the run resumes at page 3.
        when(state.nextPage("popular")).thenReturn(Optional.of(3));
        pagesRequested.clear();
        SeedReport resumed = service(properties(null, 500, null, null)).seed(new SeedPlan(9, List.of(), true));

        assertThat(resumed.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(pagesRequested).first().isEqualTo(3);
        verify(state).clear("popular");
    }

    @Test
    void theGuardStopsAnExportBetweenChunksWithTheCursorKept() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 500));
        when(catalog.details(200)).thenAnswer(inv -> {
            databaseBytes.set(1_000 * MB);
            return good(200);
        });

        SeedReport report = service(properties()).seed(exportOnly());

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.fetched()).isEqualTo(200);
        verify(state).save("export", 201, "2026-10-17");
        verify(catalog, never()).details(201);
        verify(state, never()).clear(anyString());
    }

    @Test
    void zeroTurnsTheGuardOff() {
        databaseBytes.set(100_000 * MB);
        probe = () -> {
            throw new AssertionError("the probe is not consulted when the guard is off");
        };

        SeedReport report = service(properties(null, 0, null, null)).seed(new SeedPlan(0, List.of(1), false));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void aSizeLookupThatFailsAborts() {
        probe = () -> {
            throw new IllegalStateException("permission denied");
        };

        assertThat(service(properties()).seed(new SeedPlan(0, List.of(1), false)).outcome())
                .isEqualTo(Outcome.ABORTED);
        verify(catalog, never()).details(anyInt());
    }

    // --- source selection ---

    @Test
    void theDefaultSourcesArePopularAndIdsOnly() {
        when(catalog.popular(null, 1)).thenReturn(listPage(1, 1, 1));

        SeedReport report = service(properties()).seedAll();

        assertThat(report.fetched()).isEqualTo(1);
        verifyNoInteractions(export);
        verify(catalog, never()).discoverByYear(anyInt(), anyInt(), anyInt());
    }

    @Test
    void sourcesSelectWhatAFullSeedReads() throws Exception {
        when(export.load(TODAY)).thenReturn(exportOf(TODAY, 2));
        when(catalog.discoverByYear(anyInt(), anyInt(), anyInt())).thenReturn(listPage(1, 1, 3));
        CatalogSeedProperties bulkOnly = properties(List.of(Source.EXPORT, Source.DISCOVER), null,
                new Discover(2026, 2026, 1, null), null);

        SeedReport report = service(bulkOnly).seedAll();

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.fetched()).isEqualTo(3);
        verify(catalog, never()).popular(any(), anyInt());
        // Export before discover.
        InOrder order = inOrder(export, catalog);
        order.verify(export).load(TODAY);
        order.verify(catalog).discoverByYear(2026, 10, 1);
    }

    @Test
    void theTopUpNeverReadsTheBulkSources() {
        when(catalog.popular(eq(null), anyInt())).thenReturn(listPage(1, 1));
        CatalogSeedProperties all = properties(List.of(Source.values()), null, null, null);

        service(all).topUp();

        verifyNoInteractions(export);
        verify(catalog, never()).discoverByYear(anyInt(), anyInt(), anyInt());
    }
}
