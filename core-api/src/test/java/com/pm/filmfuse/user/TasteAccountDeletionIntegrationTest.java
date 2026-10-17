package com.pm.filmfuse.user;

import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DELETE /me removes every Phase 1b row of the account (FK cascades) and nobody else's. */
class TasteAccountDeletionIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String[] TABLES = {
            "user_genre_prefs", "user_mood_prefs", "user_favorite_movies", "ratings", "rating_unseen",
            "rec_dismissals", "user_interest_vectors", "user_interest_vector_history"};

    private void fill(TestUser user) throws Exception {
        int genre = uniqueGenre();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        setMoods(user, "[{\"moodTag\": \"cozy\", \"score\": 9}]");
        setFavorites(user, movie(genre, "PG", 1));
        rate(user, movie(genre, "PG", 1), 7);
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + movie(genre, "PG", 1) + ", \"unseen\": true}]")
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/me/recommendations/{id}/dismiss", movie(genre, "PG", 1)).with(user.auth()))
                .andExpect(status().isNoContent());
        buildVector(user);
    }

    @Test
    void deletingTheAccountCascadesEveryTasteRow() throws Exception {
        TestUser leaving = user();
        TestUser staying = user();
        fill(leaving);
        fill(staying);
        for (String table : TABLES) {
            assertThat(count(table, leaving.id())).as(table).isPositive();
        }

        mockMvc.perform(delete("/api/v1/me").with(leaving.auth())).andExpect(status().isNoContent());

        for (String table : TABLES) {
            assertThat(count(table, leaving.id())).as(table).isZero();
            assertThat(count(table, staying.id())).as(table).isPositive();
        }
    }
}
