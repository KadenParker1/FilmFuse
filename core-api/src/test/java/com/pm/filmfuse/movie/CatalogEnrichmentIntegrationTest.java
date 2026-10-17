package com.pm.filmfuse.movie;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.repository.MovieRepository;
import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.repository.MovieContentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The list-cached-movies bug: movies cached from search / popular carry no certification, so a
 * PG-13 member's pool was empty. Enrichment looks their details up once and the pool fills.
 */
class CatalogEnrichmentIntegrationTest extends AbstractPhase5IntegrationTest {

    @Autowired
    private MovieContentRepository contentRepository;

    @Autowired
    private MovieRepository movieRepository;

    private static final ContentFilter PG13 = new ContentFilter(Certification.PG_13, Map.of(), false);

    /** A list-cached movie (no certification, never checked) more popular than anything before it. */
    private int listCached(int genre) {
        return topMovie(genre, null);
    }

    private void catalogKnows(int id, int genre, String certification, List<String> keywords) {
        Double popularity = jdbc.queryForObject("select popularity from app.movies where id = ?", Double.class, id);
        doReturn(new CatalogMovie(id, "Movie " + id, 2001, 100, null, null, null, List.of(genre), popularity, 7.0,
                certification, keywords)).when(catalogClient).details(id);
    }

    private Map<String, Object> movieRow(int id) {
        return jdbc.queryForMap("select certification, certification_checked_at, keywords::text as keywords "
                + "from app.movies where id = ?", id);
    }

    private UUID pg13Group(TestUser owner, TestUser member) throws Exception {
        UUID groupId = activeGroup(owner, member);
        setComfort(member, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {}, \"allowUnrated\": false}");
        return groupId;
    }

    @Test
    void aPg13MemberGetsResultsOnceListCachedMoviesAreEnriched() throws Exception {
        int genre = uniqueGenre();
        int pg13 = listCached(genre);
        int pg = listCached(genre);
        int r = listCached(genre);
        catalogKnows(pg13, genre, "PG-13", List.of());
        catalogKnows(pg, genre, "PG", List.of());
        catalogKnows(r, genre, "R", List.of());
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = pg13Group(owner, member);

        // The bug: nothing list-cached passes a PG-13 cap with allowUnrated = false.
        CandidateCriteria criteria = new CandidateCriteria(null, null, List.of(genre));
        assertThat(contentRepository.eligiblePool(PG13, criteria, List.of(), 200)).isEmpty();

        String session = json(createGroupSession(owner, groupId, genre).andExpect(status().isCreated()));

        assertThat(resultIds(session)).containsExactlyInAnyOrder(pg13, pg);
        assertThat(movieRow(pg13).get("certification")).isEqualTo("PG-13");
        assertThat(movieRow(r).get("certification")).isEqualTo("R");
        for (int id : new int[] {pg13, pg, r}) {
            assertThat(movieRow(id).get("certification_checked_at")).as("checked %d", id).isNotNull();
        }
    }

    @Test
    void checkedMoviesAreNeverLookedUpAgain() throws Exception {
        int genre = uniqueGenre();
        int unrated = listCached(genre);
        catalogKnows(unrated, genre, null, List.of());
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = pg13Group(owner, member);

        createGroupSession(owner, groupId, genre).andExpect(status().isCreated());
        createGroupSession(owner, groupId, genre).andExpect(status().isCreated());

        verify(catalogClient, times(1)).details(unrated);
        assertThat(movieRow(unrated).get("certification")).isNull();
        assertThat(movieRow(unrated).get("certification_checked_at")).isNotNull();
    }

    @Test
    void anIdTheCatalogDoesNotKnowIsCheckedAndSkipped() throws Exception {
        int genre = uniqueGenre();
        int gone = listCached(genre);
        doThrow(new NotFoundException("Movie not found.")).when(catalogClient).details(gone);
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = pg13Group(owner, member);

        String session = json(createGroupSession(owner, groupId, genre).andExpect(status().isCreated()));
        createGroupSession(owner, groupId, genre).andExpect(status().isCreated());

        assertThat(resultIds(session)).isEmpty();
        verify(catalogClient, times(1)).details(gone);
        assertThat(movieRow(gone).get("certification_checked_at")).isNotNull();
    }

    @Test
    void aCatalogOutageLeavesMoviesUncheckedAndTheSessionStillStarts() throws Exception {
        int genre = uniqueGenre();
        int movie = listCached(genre);
        doThrow(new UpstreamUnavailableException("down", null)).when(catalogClient).details(anyInt());
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = pg13Group(owner, member);

        createGroupSession(owner, groupId, genre).andExpect(status().isCreated());

        assertThat(movieRow(movie).get("certification_checked_at")).isNull();
        org.mockito.Mockito.reset(catalogClient);
    }

    @Test
    void laterListUpsertsNeverEraseWhatEnrichmentLearned() throws Exception {
        int genre = uniqueGenre();
        int movie = listCached(genre);
        catalogKnows(movie, genre, "PG", List.of("heist", "friendship"));
        TestUser owner = user();
        TestUser member = user();
        createGroupSession(owner, pg13Group(owner, member), genre).andExpect(status().isCreated());
        Object checkedAt = movieRow(movie).get("certification_checked_at");

        CatalogMovie asList = new CatalogMovie(movie, "Movie " + movie, 2001, null, null, null, null, List.of(genre),
                5.0, 7.0);
        movieRepository.upsertAll(List.of(asList), Instant.now());

        Map<String, Object> row = movieRow(movie);
        assertThat(row.get("certification")).isEqualTo("PG");
        assertThat(row.get("keywords")).isEqualTo("{heist,friendship}");
        assertThat(row.get("certification_checked_at")).isEqualTo(checkedAt);
    }

    @Test
    void filtersWithoutLimitsAndFullPoolsDoNotCallTheCatalog() throws Exception {
        int genre = uniqueGenre();
        listCached(genre);
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        clearInvocations(catalogClient);

        createAdHocSession(a, genre, b).andExpect(status().isCreated());
        verify(catalogClient, never()).details(anyInt());

        // allowUnrated: unknown certifications already pass, nothing to look up.
        setComfort(b, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {}, \"allowUnrated\": true}");
        setComfort(a, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {}, \"allowUnrated\": true}");
        createAdHocSession(a, genre, b).andExpect(status().isCreated());
        verify(catalogClient, never()).details(anyInt());
    }

    @Test
    void aDetailsRequestMarksTheMovieChecked() throws Exception {
        int genre = uniqueGenre();
        int id = java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE);
        doReturn(new CatalogMovie(id, "Fresh", 2020, 90, null, null, null, List.of(genre), 1.0, 6.0, "G", List.of()))
                .when(catalogClient).details(id);

        mockMvc.perform(get("/api/v1/movies/{id}", id).with(user().auth())).andExpect(status().isOk());

        assertThat(movieRow(id).get("certification")).isEqualTo("G");
        assertThat(movieRow(id).get("certification_checked_at")).isNotNull();
    }
}
