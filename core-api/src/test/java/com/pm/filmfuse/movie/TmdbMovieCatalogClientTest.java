package com.pm.filmfuse.movie;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.ChangedIdsPage;
import com.pm.filmfuse.movie.catalog.TmdbMovieCatalogClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParamCount;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class TmdbMovieCatalogClientTest {

    private static final String BASE = "https://tmdb.test/3";

    private MockRestServiceServer server;
    private TmdbMovieCatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TmdbMovieCatalogClient(builder, BASE, "test-token");
    }

    @Test
    void searchSendsTokenAndParamsAndMapsResults() {
        server.expect(requestTo(startsWith(BASE + "/search/movie?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(queryParam("query", "matrix"))
                .andExpect(queryParam("include_adult", "false"))
                .andExpect(queryParam("language", "en-US"))
                .andExpect(queryParam("page", "2"))
                .andRespond(withSuccess("""
                        {"page": 2, "total_pages": 3, "total_results": 41, "results": [
                          {"id": 603, "title": "The Matrix", "release_date": "1999-03-30",
                           "poster_path": "/p.jpg", "backdrop_path": "/b.jpg", "overview": "Neo.",
                           "genre_ids": [28, 878], "popularity": 99.5, "vote_average": 8.2, "adult": false},
                          {"id": 1, "title": "Undated", "release_date": "", "poster_path": null,
                           "overview": "", "genre_ids": [], "popularity": 1.0, "vote_average": 0}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CatalogPage page = client.search("matrix", 2);

        server.verify();
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.movies()).hasSize(2);
        CatalogMovie matrix = page.movies().get(0);
        assertThat(matrix).isEqualTo(new CatalogMovie(603, "The Matrix", 1999, null, "/p.jpg", "/b.jpg", "Neo.",
                java.util.List.of(28, 878), 99.5, 8.2));
        CatalogMovie undated = page.movies().get(1);
        assertThat(undated.releaseYear()).isNull();
        assertThat(undated.posterPath()).isNull();
        assertThat(undated.overview()).isNull();
        assertThat(undated.genreIds()).isEmpty();
    }

    @Test
    void searchEncodesReservedCharactersInTheQuery() {
        server.expect(requestTo(startsWith(BASE + "/search/movie?query=a%26b%3Dc")))
                .andRespond(withSuccess("{\"page\":1,\"total_pages\":1,\"results\":[]}", MediaType.APPLICATION_JSON));

        CatalogPage page = client.search("a&b=c", 1);

        server.verify();
        assertThat(page.movies()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void popularWithoutGenreUsesMoviePopular() {
        server.expect(requestTo(startsWith(BASE + "/movie/popular?")))
                .andExpect(queryParam("language", "en-US"))
                .andExpect(queryParam("page", "1"))
                .andExpect(queryParamCount(2))
                .andRespond(withSuccess("{\"page\":1,\"total_pages\":500,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.popular(null, 1).hasNext()).isTrue();
        server.verify();
    }

    @Test
    void popularWithGenreUsesDiscoverSortedByPopularity() {
        server.expect(requestTo(startsWith(BASE + "/discover/movie?")))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(queryParam("sort_by", "popularity.desc"))
                .andExpect(queryParam("with_genres", "27"))
                .andExpect(queryParam("include_adult", "false"))
                .andExpect(queryParam("language", "en-US"))
                .andExpect(queryParam("page", "3"))
                .andRespond(withSuccess("""
                        {"page": 3, "total_pages": 3, "results": [
                          {"id": 694, "title": "The Shining", "release_date": "1980-05-23", "genre_ids": [27, 53],
                           "popularity": 50.1, "vote_average": 8.2}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CatalogPage page = client.popular(27, 3);

        server.verify();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.movies()).singleElement().satisfies(movie -> {
            assertThat(movie.id()).isEqualTo(694);
            assertThat(movie.genreIds()).containsExactly(27, 53);
        });
    }

    @Test
    void pagesBeyondTmdbsLimitHaveNoNext() {
        server.expect(requestTo(startsWith(BASE + "/movie/popular?")))
                .andRespond(withSuccess("{\"page\":500,\"total_pages\":40000,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.popular(null, 500).hasNext()).isFalse();
    }

    @Test
    void detailsMapsGenresObjectsAndRuntime() {
        server.expect(requestTo(BASE + "/movie/550?language=en-US&append_to_response=release_dates,keywords"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id": 550, "title": "Fight Club", "release_date": "1999-10-15", "runtime": 139,
                         "poster_path": "/fc.jpg", "backdrop_path": "/fcb.jpg", "overview": "Soap.",
                         "genres": [{"id": 18, "name": "Drama"}], "popularity": 61.4, "vote_average": 8.4,
                         "budget": 63000000}
                        """, MediaType.APPLICATION_JSON));

        CatalogMovie movie = client.details(550);

        server.verify();
        assertThat(movie).isEqualTo(new CatalogMovie(550, "Fight Club", 1999, 139, "/fc.jpg", "/fcb.jpg", "Soap.",
                java.util.List.of(18), 61.4, 8.4));
    }

    @Test
    void zeroRuntimeMeansUnknown() {
        server.expect(requestTo(BASE + "/movie/7?language=en-US&append_to_response=release_dates,keywords"))
                .andRespond(withSuccess("{\"id\":7,\"title\":\"Short\",\"runtime\":0,\"genres\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.details(7).runtime()).isNull();
    }

    @Test
    void notFoundBecomesNotFoundException() {
        server.expect(requestTo(BASE + "/movie/999999999?language=en-US&append_to_response=release_dates,keywords"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"status_code\":34,\"status_message\":\"The resource you requested could not be found.\"}"));

        assertThatThrownBy(() -> client.details(999999999))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Movie not found.");
    }

    @Test
    void serverErrorBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/search/movie")))
                .andRespond(withServerError().body("{\"status_message\":\"internal secret detail\"}"));

        assertThatThrownBy(() -> client.search("x", 1))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessage("Movie data is temporarily unavailable.");
    }

    @Test
    void badTokenBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/movie/popular")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.popular(null, 1))
                .isInstanceOf(com.pm.filmfuse.movie.catalog.CatalogAuthenticationException.class)
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessage("Movie data is temporarily unavailable.");
    }

    @Test
    void rateLimitBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/movie/1")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.details(1)).isInstanceOf(UpstreamUnavailableException.class)
                .isNotInstanceOf(com.pm.filmfuse.movie.catalog.CatalogAuthenticationException.class);
    }

    @Test
    void timeoutBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/movie/1")))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.details(1))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void unreadableBodyBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/movie/1")))
                .andRespond(withSuccess("<html>gateway</html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.details(1)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void detailsTakeTheUsTheatricalCertificationAndKeywords() {
        server.expect(requestTo(BASE + "/movie/155?language=en-US&append_to_response=release_dates,keywords"))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id": 155, "title": "The Dark Knight", "release_date": "2008-07-16", "runtime": 152,
                         "genres": [{"id": 18, "name": "Drama"}, {"id": 28, "name": "Action"}],
                         "popularity": 145.0, "vote_average": 8.5,
                         "release_dates": {"results": [
                           {"iso_3166_1": "GB", "release_dates": [{"certification": "12A", "type": 3}]},
                           {"iso_3166_1": "US", "release_dates": [
                             {"certification": "", "type": 1},
                             {"certification": "NR", "type": 4},
                             {"certification": "PG-13", "type": 3}
                           ]}
                         ]},
                         "keywords": {"keywords": [
                           {"id": 1, "name": "Joker"}, {"id": 2, "name": " Vigilante "}, {"id": 3, "name": "joker"},
                           {"id": 4, "name": ""}, {"id": 5, "name": null}
                         ]}}
                        """, MediaType.APPLICATION_JSON));

        CatalogMovie movie = client.details(155);

        server.verify();
        assertThat(movie.certification()).isEqualTo("PG-13");
        assertThat(movie.keywords()).containsExactly("joker", "vigilante");
        assertThat(movie.hasContentData()).isTrue();
        assertThat(movie.asListResult().certification()).isNull();
        assertThat(movie.asListResult().keywords()).isEmpty();
        assertThat(movie.asListResult().runtime()).isNull();
    }

    @Test
    void withoutATheatricalCertificationTheFirstNonEmptyUsOneIsUsed() {
        server.expect(requestTo(BASE + "/movie/9?language=en-US&append_to_response=release_dates,keywords"))
                .andRespond(withSuccess("""
                        {"id": 9, "title": "Streaming Only",
                         "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [
                           {"certification": "", "type": 3}, {"certification": "R", "type": 4},
                           {"certification": "PG", "type": 5}]}]}}
                        """, MediaType.APPLICATION_JSON));
        CatalogMovie movie = client.details(9);
        assertThat(movie.certification()).isEqualTo("R");
        assertThat(movie.keywords()).isEmpty();
    }

    @Test
    void unknownOrMissingCertificationsAreNull() {
        server.expect(requestTo(BASE + "/movie/10?language=en-US&append_to_response=release_dates,keywords"))
                .andRespond(withSuccess("""
                        {"id": 10, "title": "Unrated",
                         "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [
                           {"certification": "NR", "type": 3}]}]}, "keywords": {"keywords": []}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.details(10).certification()).isNull();

        server.reset();
        server.expect(requestTo(BASE + "/movie/11?language=en-US&append_to_response=release_dates,keywords"))
                .andRespond(withSuccess("""
                        {"id": 11, "title": "Foreign",
                         "release_dates": {"results": [{"iso_3166_1": "FR", "release_dates": [
                           {"certification": "U", "type": 3}]}]}}
                        """, MediaType.APPLICATION_JSON));
        CatalogMovie foreign = client.details(11);
        assertThat(foreign.certification()).isNull();
        assertThat(foreign.hasContentData()).isFalse();
    }

    @Test
    void everyMpaaCertificationIsRecognised() {
        for (String certification : java.util.List.of("G", "PG", "PG-13", "R", "NC-17")) {
            assertThat(new CatalogMovie(1, "T", null, null, null, null, null, null, null, null, certification, null)
                    .certification()).isEqualTo(certification);
        }
        assertThat(new CatalogMovie(1, "T", null, null, null, null, null, null, null, null, "pg-13", null)
                .certification()).isEqualTo("PG-13");
        assertThat(new CatalogMovie(1, "T", null, null, null, null, null, null, null, null, "TV-MA", null)
                .certification()).isNull();
    }

    @Test
    void discoverByYearSortsByVoteCountAndMapsVoteCounts() {
        server.expect(requestTo(startsWith(BASE + "/discover/movie?")))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(queryParam("primary_release_year", "1994"))
                .andExpect(queryParam("sort_by", "vote_count.desc"))
                .andExpect(queryParam("vote_count.gte", "25"))
                .andExpect(queryParam("include_adult", "false"))
                .andExpect(queryParam("include_video", "false"))
                .andExpect(queryParam("language", "en-US"))
                .andExpect(queryParam("page", "4"))
                .andExpect(queryParamCount(7))
                .andRespond(withSuccess("""
                        {"page": 4, "total_pages": 12, "results": [
                          {"id": 680, "title": "Pulp Fiction", "release_date": "1994-09-10", "poster_path": "/p.jpg",
                           "overview": "Crime.", "genre_ids": [53, 80], "popularity": 60.5, "vote_average": 8.5,
                           "vote_count": 29001}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CatalogPage page = client.discoverByYear(1994, 25, 4);

        server.verify();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.movies()).singleElement().satisfies(movie -> {
            assertThat(movie.id()).isEqualTo(680);
            assertThat(movie.voteCount()).isEqualTo(29001);
            assertThat(movie.runtime()).isNull();
        });
    }

    @Test
    void detailsCarryTheVoteCount() {
        server.expect(requestTo(startsWith(BASE + "/movie/680?")))
                .andRespond(withSuccess("""
                        {"id": 680, "title": "Pulp Fiction", "runtime": 154, "vote_count": 29001}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.details(680).voteCount()).isEqualTo(29001);
    }

    @Test
    void changedMovieIdsSendsTheWindowAndPageAndDropsAdultAndMalformedEntries() {
        server.expect(requestTo(startsWith(BASE + "/movie/changes?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(queryParam("start_date", "2026-10-11"))
                .andExpect(queryParam("end_date", "2026-10-18"))
                .andExpect(queryParam("page", "3"))
                .andExpect(queryParamCount(3))
                .andRespond(withSuccess("""
                        {"page": 3, "total_pages": 41, "total_results": 4012, "results": [
                          {"id": 680, "adult": false},
                          {"id": 99, "adult": true},
                          {"id": 603},
                          {"adult": false},
                          {"id": -4, "adult": false},
                          null
                        ]}
                        """, MediaType.APPLICATION_JSON));

        ChangedIdsPage page = client.changedMovieIds(java.time.LocalDate.of(2026, 10, 11),
                java.time.LocalDate.of(2026, 10, 18), 3);

        server.verify();
        assertThat(page.ids()).containsExactly(680, 603);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(41);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void anEmptyChangeListIsOnePageWithNothingNext() {
        server.expect(requestTo(startsWith(BASE + "/movie/changes?")))
                .andRespond(withSuccess("""
                        {"page": 1, "total_pages": 0, "results": []}
                        """, MediaType.APPLICATION_JSON));

        ChangedIdsPage page = client.changedMovieIds(java.time.LocalDate.of(2026, 10, 11),
                java.time.LocalDate.of(2026, 10, 18), 1);

        assertThat(page.ids()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void changeListErrorsMapLikeEveryOtherRequest() {
        server.expect(requestTo(startsWith(BASE + "/movie/changes?"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(startsWith(BASE + "/movie/changes?"))).andRespond(withServerError());
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 18);

        assertThatThrownBy(() -> client.changedMovieIds(day, day, 1))
                .isInstanceOf(com.pm.filmfuse.movie.catalog.CatalogAuthenticationException.class);
        assertThatThrownBy(() -> client.changedMovieIds(day, day, 1))
                .isInstanceOf(UpstreamUnavailableException.class)
                .isNotInstanceOf(com.pm.filmfuse.movie.catalog.CatalogAuthenticationException.class);
    }

    @Test
    void aClientWithoutChangeSupportSaysSo() {
        com.pm.filmfuse.movie.catalog.MovieCatalogClient minimal = new com.pm.filmfuse.movie.catalog.MovieCatalogClient() {
            @Override
            public CatalogPage search(String query, int page) {
                return null;
            }

            @Override
            public CatalogPage popular(Integer genreId, int page) {
                return null;
            }

            @Override
            public CatalogMovie details(int id) {
                return null;
            }
        };
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 18);

        assertThatThrownBy(() -> minimal.changedMovieIds(day, day, 1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
