package com.pm.filmfuse.movie;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Caching a movie's details stores its certification and keywords and derives
 * movie_content_levels (source "keywords"); list results never erase them; manual rows win.
 */
class MovieContentLevelIntegrationTest extends AbstractIntegrationTest {

    private static final int PULP_FICTION = 680;
    private static final int SPIRITED_AWAY = 129;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private void fetchDetailsFresh(int id) throws Exception {
        jdbc.update("update app.movies set cached_at = now() - interval '30 days' where id = ?", id);
        mockMvc.perform(get("/api/v1/movies/{id}", id).with(someUser())).andExpect(status().isOk());
    }

    private Map<String, String> levels(int id) {
        return jdbc.queryForList("select dimension, level from app.movie_content_levels where movie_id = ?", id)
                .stream().collect(Collectors.toMap(r -> (String) r.get("dimension"), r -> (String) r.get("level")));
    }

    @Test
    void detailsStoreCertificationKeywordsAndDerivedLevels() throws Exception {
        fetchDetailsFresh(PULP_FICTION);

        assertThat(jdbc.queryForObject("select certification from app.movies where id = ?", String.class, PULP_FICTION))
                .isEqualTo("R");
        List<String> keywords = jdbc.queryForList("select unnest(keywords) from app.movies where id = ?", String.class,
                PULP_FICTION);
        assertThat(keywords).contains("heroin", "profanity", "graphic violence");
        assertThat(levels(PULP_FICTION)).containsEntry("drugs", "strong").containsEntry("language", "strong")
                .containsEntry("violence", "strong");
        assertThat(jdbc.queryForList("select distinct source from app.movie_content_levels where movie_id = ?",
                String.class, PULP_FICTION)).containsExactly("keywords");
    }

    @Test
    void aFamilyCertificationFillsInDimensionsWithoutKeywords() throws Exception {
        fetchDetailsFresh(SPIRITED_AWAY);
        assertThat(jdbc.queryForObject("select certification from app.movies where id = ?", String.class, SPIRITED_AWAY))
                .isEqualTo("PG");
        // "witch" / "dragon" -> mild frights; the rest from the PG ceiling.
        assertThat(levels(SPIRITED_AWAY)).containsEntry("frightening", "mild").containsEntry("drugs", "none")
                .containsEntry("sexual", "mild").hasSize(5);
    }

    @Test
    void listResultsNeverEraseContentData() throws Exception {
        fetchDetailsFresh(PULP_FICTION);
        // Popular / search results carry no certification or keywords.
        mockMvc.perform(get("/api/v1/movies/search").param("q", "Pulp").with(someUser())).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select certification from app.movies where id = ?", String.class, PULP_FICTION))
                .isEqualTo("R");
        assertThat(jdbc.queryForObject("select cardinality(keywords) from app.movies where id = ?", Integer.class,
                PULP_FICTION)).isPositive();
        assertThat(levels(PULP_FICTION)).isNotEmpty();
    }

    @Test
    void manualLevelsAreNeverOverwrittenAndStaleDerivedOnesAreRemoved() throws Exception {
        fetchDetailsFresh(PULP_FICTION);
        jdbc.update("update app.movie_content_levels set level = 'none', source = 'manual' "
                + "where movie_id = ? and dimension = 'drugs'", PULP_FICTION);
        // A derived row for a dimension the provider no longer estimates goes away on the next fetch.
        jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) values (?, 'frightening', "
                + "'strong', 'keywords') on conflict do nothing", PULP_FICTION);

        fetchDetailsFresh(PULP_FICTION);

        assertThat(levels(PULP_FICTION)).containsEntry("drugs", "none").doesNotContainKey("frightening");
        assertThat(jdbc.queryForObject("select source from app.movie_content_levels where movie_id = ? and dimension = 'drugs'",
                String.class, PULP_FICTION)).isEqualTo("manual");
    }
}
