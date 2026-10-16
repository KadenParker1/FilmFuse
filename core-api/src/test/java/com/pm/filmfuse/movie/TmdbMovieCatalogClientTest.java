package com.pm.filmfuse.movie;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
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
        server.expect(requestTo(BASE + "/movie/550?language=en-US"))
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
        server.expect(requestTo(BASE + "/movie/7?language=en-US"))
                .andRespond(withSuccess("{\"id\":7,\"title\":\"Short\",\"runtime\":0,\"genres\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.details(7).runtime()).isNull();
    }

    @Test
    void notFoundBecomesNotFoundException() {
        server.expect(requestTo(BASE + "/movie/999999999?language=en-US"))
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
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessage("Movie data is temporarily unavailable.");
    }

    @Test
    void rateLimitBecomesUpstreamUnavailable() {
        server.expect(requestTo(startsWith(BASE + "/movie/1")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.details(1)).isInstanceOf(UpstreamUnavailableException.class);
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
}
