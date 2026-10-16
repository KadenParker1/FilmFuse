package com.pm.filmfuse.movie;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the fixture catalog (no TMDB token in tests), spied to count catalog calls. */
class MovieControllerIntegrationTest extends AbstractIntegrationTest {

    private static final int FIGHT_CLUB = 550;
    private static final int THE_MATRIX = 603;
    private static final int THE_SHINING = 694;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private MovieCatalogClient catalogClient;

    // --- search ---

    @Test
    void searchReturnsAPageOfSummaries() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(THE_MATRIX))
                .andExpect(jsonPath("$.items[0].title").value("The Matrix"))
                .andExpect(jsonPath("$.items[0].releaseYear").value(1999))
                .andExpect(jsonPath("$.items[0].posterUrl").value("https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg"))
                .andExpect(jsonPath("$.items[0].genreIds", hasSize(2)))
                .andExpect(jsonPath("$.items[0].voteAverage").value(8.2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                // Summary shape only: no detail fields leak into lists.
                .andExpect(jsonPath("$.items[0].runtime").doesNotExist())
                .andExpect(jsonPath("$.items[0].posterPath").doesNotExist());
    }

    @Test
    void searchResultsWithoutPosterHaveNullPosterUrl() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "Forrest").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(13))
                .andExpect(jsonPath("$.items[0].posterUrl").value(nullValue()));
    }

    @Test
    void searchCachesResultsIntoMoviesTable() throws Exception {
        jdbc.update("delete from app.movies where id = ?", THE_MATRIX);

        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix").with(someUser()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select title from app.movies where id = ?", String.class, THE_MATRIX))
                .isEqualTo("The Matrix");
        // List results carry no runtime.
        assertThat(jdbc.queryForObject("select runtime from app.movies where id = ?", Integer.class, THE_MATRIX))
                .isNull();
        assertThat(jdbc.queryForObject("select genre_ids::text from app.movies where id = ?", String.class, THE_MATRIX))
                .isEqualTo("{28,878}");
    }

    @Test
    void searchWithNoMatchIsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "no such film anywhere").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void searchRequiresQ() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.q").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void searchRejectsBlankQ(String q) throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", q).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.q").exists());
        verify(catalogClient, never()).search(anyString(), anyInt());
    }

    @Test
    void searchAcceptsOneHundredCharactersButNotOneHundredAndOne() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "x".repeat(100)).with(someUser()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/movies/search").param("q", "x".repeat(101)).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.q").value("Search text cannot exceed 100 characters"));
    }

    @Test
    void searchAcceptsASingleCharacter() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "a").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.nextCursor").value("2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "501", "1.5", "99999999999"})
    void invalidCursorIsABadRequest(String cursor) throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "the").param("cursor", cursor).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
        mockMvc.perform(get("/api/v1/movies/popular").param("cursor", cursor).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
    }

    // --- popular ---

    @Test
    void popularPagesThroughTheCatalogUntilNextCursorIsNull() throws Exception {
        mockMvc.perform(get("/api/v1/movies/popular").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[0].id").value(278))
                .andExpect(jsonPath("$.nextCursor").value("2"));

        mockMvc.perform(get("/api/v1/movies/popular").param("cursor", "2").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.nextCursor").value("3"));

        mockMvc.perform(get("/api/v1/movies/popular").param("cursor", "3").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(9)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        // Past the end: empty, still no cursor.
        mockMvc.perform(get("/api/v1/movies/popular").param("cursor", "4").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void popularFiltersByGenre() throws Exception {
        mockMvc.perform(get("/api/v1/movies/popular").param("genre", "27").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].genreIds", everyItem(hasItem(27))))
                .andExpect(jsonPath("$.items[*].id", hasItem(THE_SHINING)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        verify(catalogClient).popular(27, 1);
    }

    @Test
    void popularRejectsUnknownGenre() throws Exception {
        mockMvc.perform(get("/api/v1/movies/popular").param("genre", "12345").with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown genre."));
        verify(catalogClient, never()).popular(12345, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "0", "-28"})
    void popularRejectsMalformedGenre(String genre) throws Exception {
        mockMvc.perform(get("/api/v1/movies/popular").param("genre", genre).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.genre").exists());
    }

    // --- details ---

    @Test
    void detailsReturnsTheFullShape() throws Exception {
        mockMvc.perform(get("/api/v1/movies/{id}", FIGHT_CLUB).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FIGHT_CLUB))
                .andExpect(jsonPath("$.title").value("Fight Club"))
                .andExpect(jsonPath("$.releaseYear").value(1999))
                .andExpect(jsonPath("$.runtime").value(139))
                .andExpect(jsonPath("$.overview").isString())
                .andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg"))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue()))
                .andExpect(jsonPath("$.genres", hasSize(1)))
                .andExpect(jsonPath("$.genres[0].id").value(18))
                .andExpect(jsonPath("$.genres[0].name").value("Drama"))
                .andExpect(jsonPath("$.voteAverage").value(8.4))
                .andExpect(jsonPath("$.popularity").isNumber());
    }

    @Test
    void detailsBuildsBackdropUrlFromCachedBackdrop() throws Exception {
        upsertCached(9001, "Cached With Backdrop", 100, "/poster.jpg", "/backdrop.jpg", Instant.now());

        mockMvc.perform(get("/api/v1/movies/{id}", 9001).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posterUrl").value("https://image.tmdb.org/t/p/w500/poster.jpg"))
                .andExpect(jsonPath("$.backdropUrl").value("https://image.tmdb.org/t/p/w1280/backdrop.jpg"));
        verify(catalogClient, never()).details(9001);
    }

    @Test
    void unknownMovieIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/movies/{id}", 999_999_999).with(someUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Movie not found."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999"})
    void nonNumericIdIsABadRequest(String id) throws Exception {
        mockMvc.perform(get("/api/v1/movies/{id}", id).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.id").value("Must be a whole number"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    void nonPositiveIdIsABadRequest(int id) throws Exception {
        mockMvc.perform(get("/api/v1/movies/{id}", id).with(someUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.id").exists());
    }

    // --- details cache ---

    @Test
    void secondDetailsCallIsServedFromTheCache() throws Exception {
        jdbc.update("delete from app.movies where id = ?", FIGHT_CLUB);
        clearInvocations(catalogClient);

        mockMvc.perform(get("/api/v1/movies/{id}", FIGHT_CLUB).with(someUser())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/movies/{id}", FIGHT_CLUB).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime").value(139))
                .andExpect(jsonPath("$.genres[0].name").value("Drama"));

        verify(catalogClient, times(1)).details(FIGHT_CLUB);
        assertThat(jdbc.queryForObject("select runtime from app.movies where id = ?", Integer.class, FIGHT_CLUB))
                .isEqualTo(139);
    }

    @Test
    void freshCachedRowIsServedWithoutTheCatalog() throws Exception {
        upsertCached(9002, "Only In The Cache", 95, null, null, Instant.now().minus(Duration.ofDays(6)));

        mockMvc.perform(get("/api/v1/movies/{id}", 9002).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Only In The Cache"))
                .andExpect(jsonPath("$.runtime").value(95))
                .andExpect(jsonPath("$.posterUrl").value(nullValue()))
                .andExpect(jsonPath("$.genres[*].name", hasItem("Drama")));
        verify(catalogClient, never()).details(9002);
    }

    @Test
    void staleCachedRowIsRefetched() throws Exception {
        upsertCached(THE_SHINING, "Stale Title", 1, null, null, Instant.now().minus(Duration.ofDays(8)));

        mockMvc.perform(get("/api/v1/movies/{id}", THE_SHINING).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("The Shining"))
                .andExpect(jsonPath("$.runtime").value(144));

        verify(catalogClient, times(1)).details(THE_SHINING);
        Timestamp cachedAt = jdbc.queryForObject("select cached_at from app.movies where id = ?", Timestamp.class, THE_SHINING);
        assertThat(cachedAt.toInstant()).isAfter(Instant.now().minus(Duration.ofMinutes(5)));
        assertThat(jdbc.queryForObject("select title from app.movies where id = ?", String.class, THE_SHINING))
                .isEqualTo("The Shining");
    }

    @Test
    void cachedRowWithoutRuntimeIsRefetched() throws Exception {
        // A search caches the movie without runtime...
        jdbc.update("delete from app.movies where id = ?", THE_MATRIX);
        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix").with(someUser())).andExpect(status().isOk());
        clearInvocations(catalogClient);

        // ...so details still goes to the catalog once, then the cache has the runtime.
        mockMvc.perform(get("/api/v1/movies/{id}", THE_MATRIX).with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime").value(136));
        mockMvc.perform(get("/api/v1/movies/{id}", THE_MATRIX).with(someUser())).andExpect(status().isOk());
        verify(catalogClient, times(1)).details(THE_MATRIX);
    }

    @Test
    void listResultsDoNotEraseACachedRuntime() throws Exception {
        jdbc.update("delete from app.movies where id = ?", THE_MATRIX);
        mockMvc.perform(get("/api/v1/movies/{id}", THE_MATRIX).with(someUser())).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix").with(someUser())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select runtime from app.movies where id = ?", Integer.class, THE_MATRIX))
                .isEqualTo(136);
    }

    // --- upstream failure ---

    @Test
    void catalogOutageIsA502WithAFixedMessage() throws Exception {
        doThrow(new UpstreamUnavailableException("Movie data is temporarily unavailable.",
                new IllegalStateException("TMDB GET /search/movie returned 503")))
                .when(catalogClient).search(anyString(), anyInt());

        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix").with(someUser()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Movie data is temporarily unavailable."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void catalogOutageOnDetailsIsA502() throws Exception {
        jdbc.update("delete from app.movies where id = ?", FIGHT_CLUB);
        doThrow(new UpstreamUnavailableException("Movie data is temporarily unavailable."))
                .when(catalogClient).details(FIGHT_CLUB);

        mockMvc.perform(get("/api/v1/movies/{id}", FIGHT_CLUB).with(someUser()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Movie data is temporarily unavailable."));
    }

    // --- auth ---

    @Test
    void everyMovieEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/movies/search").param("q", "matrix")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/movies/popular")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/movies/popular").param("genre", "27")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/movies/{id}", FIGHT_CLUB)).andExpect(status().isUnauthorized());
        verify(catalogClient, never()).search(anyString(), anyInt());
    }

    /** Drama-only row written straight to the cache (ids 9000+ are not in the fixture catalog). */
    private void upsertCached(int id, String title, int runtime, String posterPath, String backdropPath, Instant cachedAt) {
        jdbc.update("""
                insert into app.movies (id, title, release_year, runtime, poster_path, backdrop_path, overview,
                                        genre_ids, popularity, vote_average, cached_at)
                values (?, ?, 2001, ?, ?, ?, 'Cached.', '{18}', 1.0, 7.0, ?)
                on conflict (id) do update set title = excluded.title, runtime = excluded.runtime,
                    poster_path = excluded.poster_path, backdrop_path = excluded.backdrop_path,
                    cached_at = excluded.cached_at
                """, id, title, runtime, posterPath, backdropPath, Timestamp.from(cachedAt));
    }
}
