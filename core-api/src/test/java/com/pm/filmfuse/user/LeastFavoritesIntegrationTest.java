package com.pm.filmfuse.user;

import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /me/preferences/least-favorites: optional 0-5 ranked least favourites, never overlapping the
 * favourites, a taste signal (dirty + ML request) and part of the onboarding state.
 */
class LeastFavoritesIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String LEAST = "/api/v1/me/preferences/least-favorites";
    private static final String FAVORITES = "/api/v1/me/preferences/favorites";
    private static final String STATE = "/api/v1/me/onboarding";
    private static final String COMPLETE = "/api/v1/me/onboarding/complete";
    private static final String TABLE = "user_least_favorite_movies";

    private static String ranked(int... movieIds) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < movieIds.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"movieId\": ").append(movieIds[i]).append(", \"rank\": ")
                    .append(i + 1).append("}");
        }
        return json.append("]").toString();
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(put(LEAST).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replaceAllAndComeBackByRankWithMovies() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        int c = movie(genre, "PG", 1);
        putJson(user, LEAST, "[{\"movieId\": " + a + ", \"rank\": 2}, {\"movieId\": " + b + ", \"rank\": 1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].movie.id").value(b))
                .andExpect(jsonPath("$[0].movie.title").value("Movie " + b))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].movie.id").value(a));
        assertThat(count(TABLE, user.id())).isEqualTo(2);

        // Replace, not merge.
        putJson(user, LEAST, ranked(c)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].movie.id").value(c));
        assertThat(count(TABLE, user.id())).isEqualTo(1);

        putJson(user, LEAST, "[]").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        assertThat(count(TABLE, user.id())).isZero();
    }

    @Test
    void ranksNeedNotBeContiguousAndOrderFollowsRank() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        int c = movie(genre, "PG", 1);
        putJson(user, LEAST, "[{\"movieId\": " + a + ", \"rank\": 9}, {\"movieId\": " + b + ", \"rank\": 3},"
                + " {\"movieId\": " + c + ", \"rank\": 5}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].movie.id").value(org.hamcrest.Matchers.contains(b, c, a)))
                .andExpect(jsonPath("$[*].rank").value(org.hamcrest.Matchers.contains(3, 5, 9)));
    }

    @Test
    void fiveAreAllowedAndSixAreNot() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int[] six = new int[6];
        for (int i = 0; i < 6; i++) {
            six[i] = movie(genre, "PG", 1);
        }
        putJson(user, LEAST, ranked(six)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick at most 5 least favourites."));
        assertThat(count(TABLE, user.id())).isZero();

        setLeastFavorites(user, six[0], six[1], six[2], six[3], six[4]);
        assertThat(count(TABLE, user.id())).isEqualTo(5);
    }

    @Test
    void itemsAreValidatedLikeFavourites() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        putJson(user, LEAST, "[{\"movieId\": " + a + ", \"rank\": 1}, {\"movieId\": " + a + ", \"rank\": 2}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("only once")));
        putJson(user, LEAST, "[{\"movieId\": " + a + ", \"rank\": 1}, {\"movieId\": " + b + ", \"rank\": 1}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("only once")));
        putJson(user, LEAST, "[{\"movieId\": " + a + ", \"rank\": 0}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each least favourite needs a movieId and a rank of 1 or more."));
        putJson(user, LEAST, "[{\"rank\": 1}]").andExpect(status().isBadRequest());
        putJson(user, LEAST, "[{\"movieId\": 0, \"rank\": 1}]").andExpect(status().isBadRequest());
        putJson(user, LEAST, "[null]").andExpect(status().isBadRequest());
        putJson(user, LEAST, "{\"movieId\": " + a + ", \"rank\": 1}").andExpect(status().isBadRequest());
        // Nothing was stored, nothing marked dirty.
        assertThat(count(TABLE, user.id())).isZero();
        assertThat(vectorRow(user)).isNull();
    }

    @Test
    void anUncachedMovieIsFetchedFromTheCatalogAndAnUnknownOneIs404() throws Exception {
        TestUser user = user();
        // 862 (Toy Story) is in the offline fixture catalog.
        putJson(user, LEAST, ranked(862)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movie.title").value("Toy Story"));
        putJson(user, LEAST, ranked(2147000000)).andExpect(status().isNotFound());
        assertThat(count(TABLE, user.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- overlap

    @Test
    void aFavouriteCannotAlsoBeALeastFavourite() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int loved = movie(genre, "PG", 1);
        int hated = movie(genre, "PG", 1);
        setFavorites(user, loved);

        putJson(user, LEAST, ranked(hated, loved))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A movie can't be both a favourite and a least favourite."))
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(1)))
                .andExpect(jsonPath("$.fieldErrors.leastFavorites")
                        .value("Already in your favourites: Movie " + loved + "."));
        // All or nothing: the valid one was not stored either.
        assertThat(count(TABLE, user.id())).isZero();
        assertThat(count("user_favorite_movies", user.id())).isEqualTo(1);
    }

    @Test
    void aLeastFavouriteCannotAlsoBeAFavourite() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int loved = movie(genre, "PG", 1);
        int hated = movie(genre, "PG", 1);
        setLeastFavorites(user, hated);

        putJson(user, FAVORITES, ranked(loved, hated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A movie can't be both a favourite and a least favourite."))
                .andExpect(jsonPath("$.fieldErrors.favorites")
                        .value("Already in your least favourites: Movie " + hated + "."));
        assertThat(count("user_favorite_movies", user.id())).isZero();
        assertThat(count(TABLE, user.id())).isEqualTo(1);
    }

    @Test
    void movingAMovieBetweenListsWorksOnceItLeavesTheOther() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        setFavorites(user, movie);
        putJson(user, LEAST, ranked(movie)).andExpect(status().isBadRequest());
        setFavorites(user);
        setLeastFavorites(user, movie);
        assertThat(count(TABLE, user.id())).isEqualTo(1);
    }

    @Test
    void otherUsersListsDoNotClash() throws Exception {
        TestUser one = user();
        TestUser two = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        setFavorites(one, movie);
        setLeastFavorites(two, movie);
        assertThat(count(TABLE, two.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------- taste signal

    @Test
    void writesMarkTheVectorDirtyWithTasteEdit() throws Exception {
        TestUser user = user();
        setLeastFavorites(user, movie(uniqueGenre(), "PG", 1));
        assertThat(isDirty(user)).isTrue();
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");
        assertThat(version(user)).isZero();

        buildVector(user);
        assertThat(isDirty(user)).isFalse();
        // Clearing the list is a taste edit too.
        setLeastFavorites(user);
        assertThat(isDirty(user)).isTrue();
        assertThat(dirtyReason(user)).isEqualTo("TASTE_EDIT");
    }

    @Test
    void aRejectedOverlapDoesNotMarkTheVectorDirty() throws Exception {
        TestUser user = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        setFavorites(user, movie);
        buildVector(user);
        putJson(user, LEAST, ranked(movie)).andExpect(status().isBadRequest());
        assertThat(isDirty(user)).isFalse();
    }

    // ------------------------------------------------------------------------------- onboarding

    @Test
    void onboardingStateIncludesLeastFavouritesByRank() throws Exception {
        TestUser user = user();
        mockMvc.perform(get(STATE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leastFavorites", hasSize(0)));

        int genre = uniqueGenre();
        int fav = movie(genre, "PG", 1);
        int a = movie(genre, "PG", 1);
        int b = movie(genre, "PG", 1);
        setFavorites(user, fav);
        setLeastFavorites(user, b, a);
        mockMvc.perform(get(STATE).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorites", hasSize(1)))
                .andExpect(jsonPath("$.favorites[0].movie.id").value(fav))
                .andExpect(jsonPath("$.leastFavorites", hasSize(2)))
                .andExpect(jsonPath("$.leastFavorites[0].rank").value(1))
                .andExpect(jsonPath("$.leastFavorites[0].movie.id").value(b))
                .andExpect(jsonPath("$.leastFavorites[0].movie.title").value("Movie " + b))
                .andExpect(jsonPath("$.leastFavorites[1].rank").value(2))
                .andExpect(jsonPath("$.leastFavorites[1].movie.id").value(a));
    }

    @Test
    void completionDoesNotNeedLeastFavourites() throws Exception {
        TestUser user = user();
        qualifyForCompletion(user);
        mockMvc.perform(post(COMPLETE).with(user.auth())).andExpect(status().isOk());
        awaitBuildsIdle();
    }

    @Test
    void leastFavouritesDoNotCountTowardsTheThreeFavourites() throws Exception {
        TestUser user = user();
        setGenres(user, QUALIFYING_GENRES);
        int genre = uniqueGenre();
        setFavorites(user, movie(genre, "PG", 1), movie(genre, "PG", 1));
        setLeastFavorites(user, movie(genre, "PG", 1), movie(genre, "PG", 1), movie(genre, "PG", 1));
        mockMvc.perform(post(COMPLETE).with(user.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick at least 3 all-time favourites."));
    }

    // ------------------------------------------------------------------------------- schema

    @Test
    void mlReaderCanReadTheTableButNotWriteIt() {
        assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.user_least_favorite_movies', 'SELECT')",
                Boolean.class)).isTrue();
        for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE"}) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.user_least_favorite_movies', ?)",
                    Boolean.class, privilege)).as(privilege).isFalse();
        }
    }

    @Test
    void deletingTheAccountRemovesItsLeastFavouritesOnly() throws Exception {
        TestUser leaving = user();
        TestUser staying = user();
        int movie = movie(uniqueGenre(), "PG", 1);
        setLeastFavorites(leaving, movie);
        setLeastFavorites(staying, movie);
        mockMvc.perform(delete("/api/v1/me").with(leaving.auth())).andExpect(status().isNoContent());
        assertThat(count(TABLE, leaving.id())).isZero();
        assertThat(count(TABLE, staying.id())).isEqualTo(1);
    }
}
