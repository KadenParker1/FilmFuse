package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.catalog.TmdbMovieCatalogClient;
import com.pm.filmfuse.movie.content.ContentLevelService;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.repository.MovieRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** CatalogEnrichmentService against both catalog clients (TMDB over a mock server, and the fixture). */
class CatalogEnrichmentServiceTest {

    private static final String BASE = "https://tmdb.test/3";
    private static final String DETAILS = "?language=en-US&append_to_response=release_dates,keywords";

    private final MovieRepository movies = mock(MovieRepository.class);
    private final MovieEnrichmentRepository queue = mock(MovieEnrichmentRepository.class);
    private final ContentLevelService contentLevels = mock(ContentLevelService.class);

    private CatalogEnrichmentService service(MovieCatalogClient client, int sparseBelow) {
        return new CatalogEnrichmentService(client, movies, queue, contentLevels, mock(PlatformTransactionManager.class),
                new CatalogEnrichmentProperties(null, null, null, sparseBelow), Clock.systemUTC());
    }

    @SuppressWarnings("unchecked")
    private List<CatalogMovie> upserted() {
        ArgumentCaptor<Collection<CatalogMovie>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(movies, org.mockito.Mockito.atLeastOnce()).upsertAll(captor.capture(), any());
        return captor.getAllValues().stream().flatMap(Collection::stream).toList();
    }

    @Test
    void tmdbDetailsFillInTheCertificationAndMarkTheMovieChecked() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/movie/155" + DETAILS)).andRespond(withSuccess("""
                {"id": 155, "title": "The Dark Knight", "release_date": "2008-07-16", "runtime": 152,
                 "genres": [{"id": 28, "name": "Action"}], "popularity": 145.0, "vote_average": 8.5,
                 "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [{"certification": "PG-13", "type": 3}]}]},
                 "keywords": {"keywords": [{"id": 1, "name": "joker"}]}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/movie/7" + DETAILS)).andRespond(withSuccess("""
                {"id": 7, "title": "No US rating", "genres": [], "release_dates": {"results": []}}
                """, MediaType.APPLICATION_JSON));
        when(queue.unchecked(30)).thenReturn(List.of(155, 7));

        int checked = service(new TmdbMovieCatalogClient(builder, BASE, "t"), 100).enrich(30);

        server.verify();
        assertThat(checked).isEqualTo(2);
        List<CatalogMovie> written = upserted();
        assertThat(written).extracting(CatalogMovie::id).containsExactly(155, 7);
        assertThat(written.getFirst().certification()).isEqualTo("PG-13");
        assertThat(written.getFirst().keywords()).containsExactly("joker");
        assertThat(written.get(1).certification()).isNull();
        verify(contentLevels, times(2)).refresh(anyCollection(), any());
        verify(queue).markChecked(eq(List.of(155)), any());
        verify(queue).markChecked(eq(List.of(7)), any());
    }

    @Test
    void anIdTmdbDoesNotKnowIsMarkedCheckedWithoutAWrite() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/movie/9" + DETAILS)).andRespond(withStatus(
                org.springframework.http.HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{}"));
        when(queue.unchecked(5)).thenReturn(List.of(9));

        assertThat(service(new TmdbMovieCatalogClient(builder, BASE, "t"), 100).enrich(5)).isEqualTo(1);

        verify(movies, never()).upsertAll(any(), any());
        verify(queue).markChecked(eq(List.of(9)), any());
    }

    @Test
    void aTmdbOutageStopsTheBatchAndMarksNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/movie/1" + DETAILS)).andRespond(withServerError());
        server.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(BASE + "/movie/2" + DETAILS));
        when(queue.unchecked(5)).thenReturn(List.of(1, 2));

        assertThat(service(new TmdbMovieCatalogClient(builder, BASE, "t"), 100).enrich(5)).isZero();

        server.verify();
        verify(queue, never()).markChecked(any(), any());
    }

    @Test
    void theFixtureCatalogFillsInItsCertifications() {
        when(queue.unchecked(3)).thenReturn(List.of(155, 278, 42));

        int checked = service(FixtureMovieCatalogClient.fromClasspath(), 100).enrich(3);

        assertThat(checked).isEqualTo(3);
        assertThat(upserted()).extracting(CatalogMovie::id, CatalogMovie::certification)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(155, "PG-13"), org.assertj.core.groups.Tuple.tuple(278, "R"));
        verify(queue).markChecked(eq(List.of(42)), any());
    }

    @Test
    void aSparseCacheIsSeededWithThreePopularPages() {
        when(queue.count()).thenReturn(10L);
        FixtureMovieCatalogClient fixture = FixtureMovieCatalogClient.fromClasspath();

        assertThat(service(fixture, 100).seedIfSparse()).isTrue();

        List<CatalogMovie> seeded = upserted();
        List<Integer> expected = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            fixture.popular(null, page).movies().forEach(m -> expected.add(m.id()));
        }
        assertThat(seeded).extracting(CatalogMovie::id).containsExactlyElementsOf(expected);
        assertThat(seeded).allSatisfy(m -> assertThat(m.certification()).as("list results").isNull());
    }

    @Test
    void aFullCacheOrADisabledThresholdIsNotSeeded() {
        when(queue.count()).thenReturn(100L);
        assertThat(service(FixtureMovieCatalogClient.fromClasspath(), 100).seedIfSparse()).isFalse();
        when(queue.count()).thenReturn(0L);
        assertThat(service(FixtureMovieCatalogClient.fromClasspath(), 0).seedIfSparse()).isFalse();
        verify(movies, never()).upsertAll(any(), any());
    }

    @Test
    void seedingStopsOnATmdbOutage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(startsWith(BASE + "/movie/popular"))).andRespond(withServerError());
        when(queue.count()).thenReturn(0L);

        assertThat(service(new TmdbMovieCatalogClient(builder, BASE, "t"), 100).seedIfSparse()).isFalse();
        server.verify();
        verify(movies, never()).upsertAll(any(), any());
    }
}
