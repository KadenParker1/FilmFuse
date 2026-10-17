package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUT/DELETE /ratings/{movieId}, GET /me/ratings[/{movieId}], POST /me/ratings/batch. */
class RatingIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String BATCH = "/api/v1/me/ratings/batch";

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(put("/api/v1/ratings/1").contentType(MediaType.APPLICATION_JSON).content("{\"score\": 5}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/ratings/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/ratings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/ratings/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BATCH).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------- upsert

    @Test
    void aQuickRateUsesTheDefaults() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        rate(user, movie, "{\"score\": 7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.movie.id").value(movie))
                .andExpect(jsonPath("$.movie.title").value("Movie " + movie))
                .andExpect(jsonPath("$.score").value(7))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.containsSpoilers").value(false))
                .andExpect(jsonPath("$.visibility").value("FRIENDS"))
                .andExpect(jsonPath("$.source").value("QUICK_RATE"))
                .andExpect(jsonPath("$.watchedOn").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("select source from app.ratings where user_id = ? and movie_id = ?",
                String.class, user.id(), movie)).isEqualTo("quick_rate");
    }

    @Test
    void reRatingOverwritesTheSameRow() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        String first = json(rate(user, movie, "{\"score\": 4, \"body\": \"meh\", \"visibility\": \"PUBLIC\"}")
                .andExpect(status().isOk()));
        String id = JsonPath.read(first, "$.id");
        String createdAt = JsonPath.read(first, "$.createdAt");

        rate(user, movie, """
                {"score": 9, "body": "  Better the second time  ", "containsSpoilers": true, "visibility": "PRIVATE",
                 "watchedOn": "2026-10-01", "source": "POST_WATCH"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.score").value(9))
                .andExpect(jsonPath("$.body").value("Better the second time"))
                .andExpect(jsonPath("$.containsSpoilers").value(true))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.source").value("POST_WATCH"))
                .andExpect(jsonPath("$.watchedOn").value("2026-10-01"));
        assertThat(count("ratings", user.id())).isEqualTo(1);

        // A full replacement: omitted fields go back to their defaults.
        rate(user, movie, "{\"score\": 8}").andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.visibility").value("FRIENDS"))
                .andExpect(jsonPath("$.source").value("QUICK_RATE"));
    }

    @Test
    void upsertIsValidated() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        rate(user, movie, "{\"score\": 0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.score").value("Must be between 1 and 10"));
        rate(user, movie, "{\"score\": 11}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.score").value("Must be between 1 and 10"));
        rate(user, movie, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.score").value("Score is required"));
        rate(user, movie, "{\"score\": 5, \"body\": \"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").value("A note cannot exceed 1000 characters"));
        rate(user, movie, "{\"score\": 5, \"source\": \"ONBOARDING_SWIPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "ONBOARDING_SWIPE ratings can only be sent through /me/ratings/batch."));
        rate(user, movie, "{\"score\": 5, \"visibility\": \"EVERYONE\"}").andExpect(status().isBadRequest());
        rate(user, movie, "{\"score\": 5, \"watchedOn\": \"yesterday\"}").andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/ratings/abc").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\": 5}")).andExpect(status().isBadRequest());
        assertThat(count("ratings", user.id())).isZero();
        assertThat(vectorRow(user)).isNull();

        // Exactly 1000 characters is fine.
        rate(user, movie, "{\"score\": 5, \"body\": \"" + "x".repeat(1000) + "\"}").andExpect(status().isOk());
    }

    @Test
    void anUncachedMovieIsFetchedFromTheCatalogAndAnUnknownOneIs404() throws Exception {
        TestUser user = user();
        // 129 (Spirited Away) is in the offline fixture catalog.
        rate(user, 129, "{\"score\": 10}").andExpect(status().isOk())
                .andExpect(jsonPath("$.movie.title").value("Spirited Away"));
        assertThat(jdbc.queryForObject("select count(*) from app.movies where id = 129", Integer.class)).isEqualTo(1);

        rate(user, 2147000001, "{\"score\": 10}").andExpect(status().isNotFound());
        assertThat(count("ratings", user.id())).isEqualTo(1);
    }

    @Test
    void ratingWritesMarkTheVectorDirtyWithRating() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");
        rate(user, movie, 6);
        assertThat(dirtyReason(user)).isEqualTo("RATING");

        buildVector(user);
        assertThat(isDirty(user)).isFalse();
        mockMvc.perform(delete("/api/v1/ratings/{id}", movie).with(user.auth())).andExpect(status().isNoContent());
        assertThat(isDirty(user)).isTrue();
        assertThat(dirtyReason(user)).isEqualTo("RATING");
    }

    // ------------------------------------------------------------------------------- delete / get

    @Test
    void deleteIsIdempotent() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        rate(user, movie, 6);
        mockMvc.perform(delete("/api/v1/ratings/{id}", movie).with(user.auth())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/ratings/{id}", movie).with(user.auth())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/ratings/{id}", movie).with(user.auth())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("You have not rated this movie."));

        // Deleting what was never rated changes no taste signal.
        TestUser other = user();
        mockMvc.perform(delete("/api/v1/ratings/{id}", movie).with(other.auth())).andExpect(status().isNoContent());
        assertThat(vectorRow(other)).isNull();
    }

    @Test
    void getReturnsOnlyTheCallersOwnRating() throws Exception {
        TestUser user = user();
        TestUser other = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        rate(user, movie, 3);
        mockMvc.perform(get("/api/v1/me/ratings/{id}", movie).with(user.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(3));
        mockMvc.perform(get("/api/v1/me/ratings/{id}", movie).with(other.auth())).andExpect(status().isNotFound());
    }

    @Test
    void listIsNewestFirstAcrossSourcesAndPaginates() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int swiped = movie(genre, "PG", 1);
        postJson(user, BATCH, "[{\"movieId\": " + swiped + ", \"score\": 4}]").andExpect(status().isNoContent());
        int[] quick = new int[21];
        for (int i = 0; i < quick.length; i++) {
            quick[i] = movie(genre, "PG", 1);
            CLOCK.advance(java.time.Duration.ofSeconds(1));
            rate(user, quick[i], 1 + (i % 10));
        }

        String first = json(mockMvc.perform(get("/api/v1/me/ratings").with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[0].movie.id").value(quick[20]))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty()));
        String cursor = JsonPath.read(first, "$.nextCursor");
        mockMvc.perform(get("/api/v1/me/ratings").param("cursor", cursor).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].movie.id").value(quick[0]))
                .andExpect(jsonPath("$.items[1].movie.id").value(swiped))
                .andExpect(jsonPath("$.items[1].source").value("ONBOARDING_SWIPE"))
                .andExpect(jsonPath("$.items[1].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mockMvc.perform(get("/api/v1/me/ratings").param("cursor", "garbage").with(user.auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/me/ratings").with(user().auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ------------------------------------------------------------------------------- batch

    @Test
    void batchStoresPrivateSwipesAndUnseen() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int liked = movie(genre, "PG", 1);
        int unseen = movie(genre, "PG", 1);
        postJson(user, BATCH, "[{\"movieId\": " + liked + ", \"score\": 8, \"unseen\": false},"
                + " {\"movieId\": " + unseen + ", \"score\": null, \"unseen\": true}]")
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/ratings/{id}", liked).with(user.auth()))
                .andExpect(jsonPath("$.score").value(8))
                .andExpect(jsonPath("$.source").value("ONBOARDING_SWIPE"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
        mockMvc.perform(get("/api/v1/me/ratings/{id}", unseen).with(user.auth())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("select movie_id from app.rating_unseen where user_id = ?", Integer.class, user.id()))
                .containsExactly(unseen);
        assertThat(dirtyReason(user)).isEqualTo("RATING");

        // Same swipe again: idempotent.
        postJson(user, BATCH, "[{\"movieId\": " + unseen + ", \"unseen\": true}]").andExpect(status().isNoContent());
        assertThat(count("rating_unseen", user.id())).isEqualTo(1);
    }

    @Test
    void aSwipeOnAnExistingReviewOnlyChangesTheScore() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        rate(user, movie, "{\"score\": 3, \"body\": \"note\", \"visibility\": \"PUBLIC\"}").andExpect(status().isOk());
        postJson(user, BATCH, "[{\"movieId\": " + movie + ", \"score\": 9}]").andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/ratings/{id}", movie).with(user.auth()))
                .andExpect(jsonPath("$.score").value(9))
                .andExpect(jsonPath("$.body").value("note"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.source").value("QUICK_RATE"));
    }

    @Test
    void ratingAnUnseenMovieClearsTheUnseenMark() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        postJson(user, BATCH, "[{\"movieId\": " + movie + ", \"unseen\": true}]").andExpect(status().isNoContent());
        rate(user, movie, 7);
        assertThat(count("rating_unseen", user.id())).isZero();
    }

    @Test
    void batchIsValidatedAllOrNothing() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 1);
        String sizeMessage = "Send between 1 and 50 calibration ratings.";
        String itemMessage = "Each calibration rating needs a movieId and exactly one of a score (1-10) or unseen: true.";
        postJson(user, BATCH, "[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(sizeMessage));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"movieId\": ").append(i + 1).append(", \"score\": 5}");
        }
        postJson(user, BATCH, many.append("]").toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(sizeMessage));
        // Both a score and unseen; neither; out-of-range scores; no movie id.
        for (String item : List.of(
                "{\"movieId\": " + a + ", \"score\": 5, \"unseen\": true}",
                "{\"movieId\": " + a + "}",
                "{\"movieId\": " + a + ", \"unseen\": false}",
                "{\"movieId\": " + a + ", \"score\": 0}",
                "{\"movieId\": " + a + ", \"score\": 11}",
                "{\"score\": 5}",
                "null")) {
            postJson(user, BATCH, "[{\"movieId\": " + movie(genre, "PG", 1) + ", \"score\": 5}, " + item + "]")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(itemMessage));
        }
        postJson(user, BATCH, "[{\"movieId\": " + a + ", \"score\": 5}, {\"movieId\": " + a + ", \"unseen\": true}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each movie can appear only once in a batch."));
        // An unknown movie fails the whole batch.
        postJson(user, BATCH, "[{\"movieId\": " + a + ", \"score\": 5}, {\"movieId\": 2147000002, \"score\": 5}]")
                .andExpect(status().isNotFound());
        assertThat(count("ratings", user.id())).isZero();
        assertThat(count("rating_unseen", user.id())).isZero();
        assertThat(vectorRow(user)).isNull();
    }
}
