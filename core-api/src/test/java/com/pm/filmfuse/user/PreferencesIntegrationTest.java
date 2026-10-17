package com.pm.filmfuse.user;

import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /moods and PUT /me/preferences/{genres,moods,favorites}. */
class PreferencesIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String GENRES = "/api/v1/me/preferences/genres";
    private static final String MOODS = "/api/v1/me/preferences/moods";
    private static final String FAVORITES = "/api/v1/me/preferences/favorites";

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/moods")).andExpect(status().isUnauthorized());
        for (String path : new String[] {GENRES, MOODS, FAVORITES}) {
            mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("[]"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void moodsAreTheMlServicesTagsInFixedOrderWithLabels() throws Exception {
        TestUser user = user();
        mockMvc.perform(get("/api/v1/moods").with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[*].tag").value(org.hamcrest.Matchers.contains("mind-bending", "cozy",
                        "laugh-out-loud", "edge-of-seat", "tearjerker", "visually-stunning", "dark-gritty", "feel-good")))
                .andExpect(jsonPath("$[0].label").value("Mind-bending"))
                .andExpect(jsonPath("$[6].label").value("Dark & gritty"));
    }

    // ------------------------------------------------------------------------------- list caps

    /** More items than there are genres / moods is refused before any per-item work. */
    @Test
    void listsLongerThanTheDimensionsAreRefused() throws Exception {
        TestUser user = user();
        String genres = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "{\"genreId\": 28, \"score\": 5}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        putJson(user, GENRES, genres).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send at most one score per genre."));
        String moods = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> "{\"moodTag\": \"cozy\", \"score\": 5}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        putJson(user, MOODS, moods).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send at most one score per mood."));
    }

    /** An unknown mood tag is quoted back cut short, never whole. */
    @Test
    void unknownMoodTagsAreEchoedTruncated() throws Exception {
        TestUser user = user();
        String tag = "z".repeat(5000);
        putJson(user, MOODS, "[{\"moodTag\": \"" + tag + "\", \"score\": 5}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown mood: " + "z".repeat(40) + "..."));
    }

    // ------------------------------------------------------------------------------- genres

    @Test
    void genrePrefsReplaceAllAndComeBackInVectorOrder() throws Exception {
        TestUser user = user();
        putJson(user, GENRES, "[{\"genreId\": 37, \"score\": 4}, {\"genreId\": 28, \"score\": 10}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].genreId").value(28))
                .andExpect(jsonPath("$[0].score").value(10))
                .andExpect(jsonPath("$[1].genreId").value(37));
        putJson(user, GENRES, "[{\"genreId\": 18, \"score\": 1}]")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        assertThat(count("user_genre_prefs", user.id())).isEqualTo(1);
        putJson(user, GENRES, "[]").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(count("user_genre_prefs", user.id())).isZero();
    }

    @Test
    void genrePrefsAreValidated() throws Exception {
        TestUser user = user();
        putJson(user, GENRES, "[{\"genreId\": 99999, \"score\": 5}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown genre: 99999"));
        putJson(user, GENRES, "[{\"genreId\": 28, \"score\": 5}, {\"genreId\": 28, \"score\": 6}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each genre can appear only once."));
        putJson(user, GENRES, "[{\"genreId\": 28, \"score\": 0}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Every score must be a whole number from 1 to 10."));
        putJson(user, GENRES, "[{\"genreId\": 28, \"score\": 11}]").andExpect(status().isBadRequest());
        putJson(user, GENRES, "[{\"genreId\": 28}]").andExpect(status().isBadRequest());
        putJson(user, GENRES, "[{\"score\": 5}]").andExpect(status().isBadRequest());
        putJson(user, GENRES, "[null]").andExpect(status().isBadRequest());
        putJson(user, GENRES, "{\"genreId\": 28}").andExpect(status().isBadRequest());
        // Nothing was stored, nothing marked dirty.
        assertThat(count("user_genre_prefs", user.id())).isZero();
        assertThat(vectorRow(user)).isNull();
    }

    @Test
    void genreWritesMarkTheVectorDirtyWithTasteEdit() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        assertThat(isDirty(user)).isTrue();
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");
        assertThat(version(user)).isZero();
    }

    // ------------------------------------------------------------------------------- moods

    @Test
    void moodPrefsReplaceAllAndAreValidated() throws Exception {
        TestUser user = user();
        putJson(user, MOODS, "[{\"moodTag\": \"feel-good\", \"score\": 3}, {\"moodTag\": \"mind-bending\", \"score\": 9}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].moodTag").value("mind-bending"))
                .andExpect(jsonPath("$[1].moodTag").value("feel-good"));
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");

        putJson(user, MOODS, "[{\"moodTag\": \"spooky\", \"score\": 5}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown mood: spooky"));
        putJson(user, MOODS, "[{\"moodTag\": \"Cozy\", \"score\": 5}]").andExpect(status().isBadRequest());
        putJson(user, MOODS, "[{\"moodTag\": \"cozy\", \"score\": 5}, {\"moodTag\": \"cozy\", \"score\": 6}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each mood can appear only once."));
        putJson(user, MOODS, "[{\"moodTag\": \"cozy\", \"score\": 11}]").andExpect(status().isBadRequest());
        assertThat(count("user_mood_prefs", user.id())).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------- favourites

    @Test
    void favoritesReplaceAllAndComeBackByRankWithMovies() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        putJson(user, FAVORITES, "[{\"movieId\": " + a + ", \"rank\": 2}, {\"movieId\": " + b + ", \"rank\": 1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].movie.id").value(b))
                .andExpect(jsonPath("$[0].movie.title").value("Movie " + b))
                .andExpect(jsonPath("$[1].movie.id").value(a));
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");

        putJson(user, FAVORITES, "[]").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(count("user_favorite_movies", user.id())).isZero();
    }

    @Test
    void anUncachedFavoriteIsFetchedFromTheCatalogAndAnUnknownOneIs404() throws Exception {
        TestUser user = user();
        // 862 (Toy Story) is in the offline fixture catalog.
        putJson(user, FAVORITES, "[{\"movieId\": 862, \"rank\": 1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movie.title").value("Toy Story"));
        putJson(user, FAVORITES, "[{\"movieId\": 2147000000, \"rank\": 1}]").andExpect(status().isNotFound());
        assertThat(count("user_favorite_movies", user.id())).isEqualTo(1);
    }

    @Test
    void favoritesAreValidated() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 1; i <= 11; i++) {
            eleven.append(i == 1 ? "" : ",").append("{\"movieId\": ").append(movie(genre, "PG", 1))
                    .append(", \"rank\": ").append(i).append("}");
        }
        putJson(user, FAVORITES, eleven.append("]").toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick at most 10 favourites."));

        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        putJson(user, FAVORITES, "[{\"movieId\": " + a + ", \"rank\": 1}, {\"movieId\": " + a + ", \"rank\": 2}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("only once")));
        putJson(user, FAVORITES, "[{\"movieId\": " + a + ", \"rank\": 1}, {\"movieId\": " + b + ", \"rank\": 1}]")
                .andExpect(status().isBadRequest());
        putJson(user, FAVORITES, "[{\"movieId\": " + a + ", \"rank\": 0}]").andExpect(status().isBadRequest());
        putJson(user, FAVORITES, "[{\"rank\": 1}]").andExpect(status().isBadRequest());
        assertThat(count("user_favorite_movies", user.id())).isZero();
        assertThat(vectorRow(user)).isNull();
    }

    @Test
    void tenFavoritesAreAllowed() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int[] ids = new int[10];
        for (int i = 0; i < 10; i++) {
            ids[i] = movie(genre, "PG", 1);
        }
        setFavorites(user, ids);
        assertThat(count("user_favorite_movies", user.id())).isEqualTo(10);
    }
}
