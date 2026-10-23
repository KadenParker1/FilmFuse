package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogAuthenticationException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.catalog.TmdbMovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import com.pm.filmfuse.movie.seed.CatalogSeedService.SeedPlan;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CatalogSeedService with mocked storage, a fake clock for the pacer and a recording backoff sleeper. */
class CatalogSeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T04:00:00Z");

    private final CatalogEnrichmentService store = mock(CatalogEnrichmentService.class);
    private final MovieEnrichmentRepository movies = mock(MovieEnrichmentRepository.class);
    private final CatalogSeedStateRepository state = mock(CatalogSeedStateRepository.class);
    private final MovieCatalogClient catalog = mock(MovieCatalogClient.class);
    private final CatalogSeedRejectRepository rejects = mock(CatalogSeedRejectRepository.class);
    private final TmdbIdExport export = mock(TmdbIdExport.class);
    /** The fake database size, in bytes. */
    private final AtomicLong databaseBytes = new AtomicLong();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AtomicLong nanos = new AtomicLong();
    private int paced;
    private final List<Duration> backoffs = new ArrayList<>();
    private Sleeper backoffSleeper = backoffs::add;

    @BeforeEach
    void noneSeededAndNoCursor() {
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of());
        when(state.nextPage(anyString())).thenReturn(Optional.empty());
    }

    private CatalogSeedProperties properties(int maxRetries, int authFailureLimit) {
        return new CatalogSeedProperties(null, null, null, 20d, maxRetries, Duration.ofSeconds(1), null,
                authFailureLimit, 1, new CatalogSeedProperties.TopUp(null, null, 2));
    }

    /** Every acquire reads the time source exactly once, so counting reads counts paced requests. */
    private CatalogSeedService service(MovieCatalogClient client, CatalogSeedProperties properties) {
        RequestPacer pacer = new RequestPacer(properties.requestsPerSecond(), () -> {
            paced++;
            return nanos.get();
        }, duration -> nanos.addAndGet(duration.toNanos()));
        return new CatalogSeedService(client, store, movies, state, rejects, export, databaseBytes::get, properties,
                clock, pacer, duration -> backoffSleeper.sleep(duration));
    }

    private CatalogSeedService service() {
        return service(catalog, properties(3, 3));
    }

    private static CatalogMovie movie(int id) {
        return new CatalogMovie(id, "Movie " + id, 2020, 100, null, null, null, List.of(18), 10.0, 7.0, "PG",
                List.of("friendship"));
    }

    private static CatalogPage page(int page, int totalPages, Integer... ids) {
        return new CatalogPage(Arrays.stream(ids).map(id -> movie(id).asListResult()).toList(), page, totalPages);
    }

    @Test
    void theFixtureCatalogIsSeededCompletelyWithCertificationsAndTheCursorIsClearedAtTheEnd() throws Exception {
        FixtureMovieCatalogClient fixture = FixtureMovieCatalogClient.fromClasspath();

        SeedReport report = service(fixture, properties(3, 3)).seed(new SeedPlan(500, List.of(), true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.pages()).isEqualTo(3);
        assertThat(report.candidates()).isEqualTo(49);
        assertThat(report.fetched()).isEqualTo(49);
        assertThat(report.skippedAlreadySeeded() + report.notFound() + report.failed()).isZero();
        ArgumentCaptor<CatalogMovie> stored = ArgumentCaptor.forClass(CatalogMovie.class);
        verify(store, times(49)).storeDetails(stored.capture());
        assertThat(stored.getAllValues()).allMatch(CatalogMovie::hasContentData);
        verify(store, times(3)).storeListResults(any());
        verify(state).saveNextPage("popular", 2);
        verify(state).saveNextPage("popular", 3);
        verify(state).saveNextPage("popular", 4);
        verify(state).clear("popular");
        // 3 list pages + 49 details, 50 ms apart.
        assertThat(paced).isEqualTo(52);
        assertThat(nanos.get()).isEqualTo(Duration.ofMillis(51 * 50).toNanos());
        assertThat(backoffs).isEmpty();
    }

    @Test
    void moviesCheckedWithinRefreshAfterAreSkipped() {
        when(catalog.popular(null, 1)).thenReturn(page(1, 1, 1, 2, 3));
        when(catalog.details(2)).thenReturn(movie(2));
        when(movies.checkedSince(anyCollection(), any())).thenReturn(Set.of(1, 3));

        SeedReport report = service().seed(new SeedPlan(5, List.of(), true));

        verify(movies).checkedSince(List.of(1, 2, 3), NOW.minus(Duration.ofDays(7)));
        assertThat(report.skippedAlreadySeeded()).isEqualTo(2);
        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.candidates()).isEqualTo(3);
        verify(catalog, never()).details(1);
        verify(catalog, never()).details(3);
        verify(store).storeDetails(movie(2));
    }

    @Test
    void anUnknownIdIsCountedAndMarkedCheckedWithoutRetries() {
        when(catalog.details(7)).thenThrow(new NotFoundException("Movie not found."));
        when(catalog.details(8)).thenReturn(movie(8));

        SeedReport report = service().seed(new SeedPlan(0, List.of(7, 8), true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.notFound()).isEqualTo(1);
        assertThat(report.fetched()).isEqualTo(1);
        verify(store).markChecked(7);
        verify(catalog, times(1)).details(7);
        assertThat(backoffs).isEmpty();
        verifyNoInteractions(state);
    }

    @Test
    void anOutageIsRetriedWithExponentialBackoff() {
        when(catalog.details(5))
                .thenThrow(new UpstreamUnavailableException("down"))
                .thenThrow(new UpstreamUnavailableException("down"))
                .thenReturn(movie(5));

        SeedReport report = service().seed(new SeedPlan(0, List.of(5), false));

        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(backoffs).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(paced).isEqualTo(3);
    }

    @Test
    void aMovieStillFailingAfterEveryRetryIsCountedAndTheRunGoesOn() {
        when(catalog.details(5)).thenThrow(new UpstreamUnavailableException("down"));
        when(catalog.details(6)).thenReturn(movie(6));

        SeedReport report = service().seed(new SeedPlan(0, List.of(5, 6), false));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.fetched()).isEqualTo(1);
        verify(catalog, times(4)).details(5);
        assertThat(backoffs).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        verify(store, never()).storeDetails(movie(5));
    }

    @Test
    void aMovieThatCannotBeStoredIsCountedFailedAndTheRunGoesOn() {
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));
        doThrow(new IllegalStateException("constraint")).when(store).storeDetails(movie(1));

        SeedReport report = service().seed(new SeedPlan(0, List.of(1, 2), false));

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.fetched()).isEqualTo(1);
        verify(store).storeDetails(movie(2));
    }

    @Test
    void repeatedRejectedTokensAbortTheRunWithoutHammeringTmdb() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith("https://tmdb.test/3/movie/popular")))
                .andRespond(withSuccess("""
                        {"page": 1, "total_pages": 9, "results": [{"id": 11, "title": "A"}, {"id": 12, "title": "B"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(3), requestTo(startsWith("https://tmdb.test/3/movie/11")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        TmdbMovieCatalogClient tmdb = new TmdbMovieCatalogClient(builder, "https://tmdb.test/3", "bad-token");

        SeedReport report = service(tmdb, properties(5, 3)).seed(new SeedPlan(500, List.of(), true));

        server.verify();
        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.fetched()).isZero();
        assertThat(backoffs).hasSize(2);
        // The page was not finished: the cursor is not advanced, nor cleared, so the next run redoes it.
        verify(state, never()).saveNextPage(anyString(), anyInt());
        verify(state, never()).clear(anyString());
    }

    @Test
    void aSingleRejectedTokenBetweenSuccessesDoesNotAbort() {
        CatalogAuthenticationException rejected = new CatalogAuthenticationException("x", null);
        when(catalog.details(1)).thenThrow(rejected).thenReturn(movie(1));
        when(catalog.details(2)).thenThrow(rejected).thenReturn(movie(2));

        SeedReport report = service(catalog, properties(3, 2)).seed(new SeedPlan(0, List.of(1, 2), false));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.fetched()).isEqualTo(2);
    }

    @Test
    void aRejectedTokenOnAPopularPageAbortsToo() {
        when(catalog.popular(null, 1)).thenThrow(new CatalogAuthenticationException("x", null));

        SeedReport report = service().seed(new SeedPlan(10, List.of(99), true));

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        verify(catalog, times(3)).popular(null, 1);
        verify(catalog, never()).details(anyInt());
    }

    @Test
    void aStoppedRunResumesAtTheStoredPage() {
        when(state.nextPage("popular")).thenReturn(Optional.of(3));
        when(catalog.popular(null, 3)).thenReturn(page(3, 4, 31));
        when(catalog.popular(null, 4)).thenReturn(page(4, 4, 41));
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));

        SeedReport report = service().seed(new SeedPlan(500, List.of(), true));

        verify(catalog, never()).popular(null, 1);
        verify(catalog, never()).popular(null, 2);
        assertThat(report.pages()).isEqualTo(2);
        assertThat(report.fetched()).isEqualTo(2);
        verify(state).saveNextPage("popular", 4);
        verify(state).saveNextPage("popular", 5);
        verify(state).clear("popular");
    }

    @Test
    void aCursorBeyondThisRunsPagesStartsOver() {
        when(state.nextPage("popular")).thenReturn(Optional.of(40));
        when(catalog.popular(null, 1)).thenReturn(page(1, 100, 1));
        when(catalog.popular(null, 2)).thenReturn(page(2, 100, 2));
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));

        SeedReport report = service().seed(new SeedPlan(2, List.of(), true));

        assertThat(report.pages()).isEqualTo(2);
        verify(catalog, never()).popular(null, 3);
    }

    @Test
    void aPopularPageThatKeepsFailingAbortsAndKeepsTheCursor() {
        when(catalog.popular(null, 1)).thenReturn(page(1, 9, 1));
        when(catalog.popular(null, 2)).thenThrow(new UpstreamUnavailableException("down"));
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));

        SeedReport report = service().seed(new SeedPlan(9, List.of(77), true));

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.pages()).isEqualTo(1);
        verify(catalog, times(4)).popular(null, 2);
        verify(state).saveNextPage("popular", 2);
        verify(state, never()).clear(anyString());
        verify(catalog, never()).details(77);
    }

    @Test
    void popularPagesAndIdsAreCombinedAndDeduplicated() {
        when(catalog.popular(null, 1)).thenReturn(page(1, 1, 1, 2));
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));

        SeedReport report = service().seed(new SeedPlan(3, List.of(2, 3), true));

        assertThat(report.candidates()).isEqualTo(3);
        assertThat(report.fetched()).isEqualTo(3);
        verify(catalog, times(1)).details(2);
        verify(catalog, never()).popular(null, 2);
    }

    @Test
    void theTopUpListsItsOwnPagesFromPageOneAndLeavesTheCursorAlone() {
        when(catalog.popular(eq(null), anyInt())).thenAnswer(inv -> page(inv.getArgument(1), 500, 1000 + (int) inv.getArgument(1)));
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));

        SeedReport report = service().topUp();

        assertThat(report.pages()).isEqualTo(2);
        verify(catalog).popular(null, 1);
        verify(catalog).popular(null, 2);
        verify(catalog, never()).popular(null, 3);
        verifyNoInteractions(state);
    }

    @Test
    void seedAllReadsTheIdFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ids.txt");
        Files.writeString(file, "# ids\n21\n22\n");
        when(catalog.details(anyInt())).thenAnswer(inv -> movie(inv.getArgument(0)));
        CatalogSeedProperties props = new CatalogSeedProperties(null, 0, file.toString(), null, null, null, null, null,
                null, null);

        SeedReport report = service(catalog, props).seedAll();

        assertThat(report.fetched()).isEqualTo(2);
        verify(catalog, never()).popular(any(), anyInt());
    }

    @Test
    void onlyOneRunAtATime() {
        AtomicReference<SeedReport> nested = new AtomicReference<>();
        CatalogSeedService service = service();
        when(catalog.popular(null, 1)).thenAnswer(inv -> {
            nested.set(service.seed(new SeedPlan(1, List.of(), false)));
            return page(1, 1);
        });

        SeedReport report = service.seed(new SeedPlan(1, List.of(), false));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(nested.get().outcome()).isEqualTo(Outcome.ALREADY_RUNNING);
        // ...and the guard is released afterwards.
        assertThat(service.seed(new SeedPlan(0, List.of(), false)).outcome()).isEqualTo(Outcome.COMPLETED);
    }

    @Test
    void anInterruptStopsTheRunAndKeepsTheInterruptFlag() {
        when(catalog.details(anyInt())).thenThrow(new UpstreamUnavailableException("down"));
        backoffSleeper = duration -> {
            throw new InterruptedException();
        };

        SeedReport report = service().seed(new SeedPlan(0, List.of(1, 2), false));

        assertThat(report.outcome()).isEqualTo(Outcome.INTERRUPTED);
        assertThat(Thread.interrupted()).isTrue();
        verify(catalog, never()).details(2);
    }

    @Test
    void backoffDoublesUpToAMinute() {
        CatalogSeedService service = service();
        assertThat(service.backoff(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(service.backoff(6)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.backoff(40)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theReportSummaryNamesEveryCount() {
        SeedReport report = new SeedReport(Outcome.COMPLETED, 3, 49, 40, 5, 3, 1, Duration.ofSeconds(12));
        assertThat(report.summary()).isEqualTo("Catalog seed COMPLETED in 12 s: 3 pages, 49 candidates, 40 fetched, "
                + "5 already seeded, 3 not found, 1 failed, 0 rejected (quality), 0 skipped (rejected earlier)");
        assertThat(new SeedReport(Outcome.ABORTED, 0, 9, 1, 0, 0, 0, 6, 2, Duration.ZERO).summary())
                .endsWith("6 rejected (quality), 2 skipped (rejected earlier)");
    }
}
