package com.pm.filmfuse.reco;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserRecommendation;
import com.pm.filmfuse.reco.ml.MlUserRecommendationRequest;
import com.pm.filmfuse.reco.service.FallbackRanker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /me/recommendations and POST /me/recommendations/{movieId}/dismiss. */
class ForYouIntegrationTest extends AbstractTasteIntegrationTest {

    private static final String FOR_YOU = "/api/v1/me/recommendations";

    @Test
    void bothRequireAToken() throws Exception {
        mockMvc.perform(get(FOR_YOU)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(FOR_YOU + "/1/dismiss")).andExpect(status().isUnauthorized());
    }

    /** A user with a built vector, ready for the ML path. */
    private TestUser userWithVector() throws Exception {
        TestUser user = user();
        setGenres(user, "[{\"genreId\": 28, \"score\": 9}]");
        buildVector(user);
        return user;
    }

    private void mlReturns(Integer... movieIds) {
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userRecommendations(any())).thenReturn(java.util.Arrays.stream(movieIds)
                .map(id -> new MlUserRecommendation(id, 8.5, "Because you like action")).toList());
    }

    private MlUserRecommendationRequest lastRequest() {
        ArgumentCaptor<MlUserRecommendationRequest> captor = ArgumentCaptor.forClass(MlUserRecommendationRequest.class);
        verify(mlClient, org.mockito.Mockito.atLeastOnce()).userRecommendations(captor.capture());
        return captor.getValue();
    }

    private List<Integer> forYouIds(TestUser user) throws Exception {
        return JsonPath.read(json(mockMvc.perform(get(FOR_YOU).with(user.auth())).andExpect(status().isOk())),
                "$.items[*].movie.id");
    }

    @Test
    void withoutAVectorTheFallbackRanksByPopularity() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int less = topMovie(genre, "PG");
        int more = topMovie(genre, "PG-13");
        mockMvc.perform(get(FOR_YOU).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].movie.id").value(more))
                .andExpect(jsonPath("$.items[0].certification").value("PG_13"))
                .andExpect(jsonPath("$.items[0].score").value(FallbackRanker.NEUTRAL_SCORE))
                .andExpect(jsonPath("$.items[0].explanation").value(FallbackRanker.EXPLANATION))
                .andExpect(jsonPath("$.items[1].movie.id").value(less));
        verify(mlClient, never()).userRecommendations(any());
    }

    @Test
    void ratedUnseenAndDismissedMoviesAreNeverRecommended() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int rated = topMovie(genre, "PG");
        int unseen = topMovie(genre, "PG");
        int dismissed = topMovie(genre, "PG");
        int eligible = topMovie(genre, "PG");
        rate(user, rated, 8);
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + unseen + ", \"unseen\": true}]")
                .andExpect(status().isNoContent());
        mockMvc.perform(post(FOR_YOU + "/{id}/dismiss", dismissed).with(user.auth())).andExpect(status().isNoContent());
        buildVector(user);
        mlReturns(rated, unseen, dismissed, eligible);

        assertThat(forYouIds(user)).containsExactly(eligible);
        MlUserRecommendationRequest request = lastRequest();
        assertThat(request.userId()).isEqualTo(user.id());
        assertThat(request.vector().interpretable()).hasSize(27);
        assertThat(request.k()).isEqualTo(100);
        assertThat(request.candidateMovieIds()).contains(eligible).doesNotContain(rated, unseen, dismissed);
        assertThat(request.excludeMovieIds()).contains(rated, unseen, dismissed);
    }

    @Test
    void favouritesAreNeverRecommended() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int favorite = topMovie(genre, "PG");
        int eligible = topMovie(genre, "PG");
        setFavorites(user, favorite);
        buildVector(user);
        mlReturns(favorite, eligible);

        assertThat(forYouIds(user)).containsExactly(eligible);
        MlUserRecommendationRequest request = lastRequest();
        assertThat(request.candidateMovieIds()).contains(eligible).doesNotContain(favorite);
        assertThat(request.excludeMovieIds()).contains(favorite);
    }

    @Test
    void theFallbackSkipsFavouritesToo() throws Exception {
        TestUser user = user();
        int genre = uniqueGenre();
        int favorite = topMovie(genre, "PG-13");
        int other = topMovie(genre, "PG");
        setFavorites(user, favorite);
        assertThat(forYouIds(user)).contains(other).doesNotContain(favorite);
        verify(mlClient, never()).userRecommendations(any());
    }

    @Test
    void favouritesAddedAfterCachingAreDroppedAtOnce() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int first = topMovie(genre, "PG");
        int second = topMovie(genre, "PG");
        mlReturns(first, second);
        assertThat(forYouIds(user)).containsExactly(first, second);
        setFavorites(user, first);
        assertThat(forYouIds(user)).containsExactly(second);
    }

    @Test
    void anIneligibleMovieFromTheRankerIsDroppedForAPg13User() throws Exception {
        TestUser user = userWithVector();
        setComfort(user, "{\"maxCertification\": \"PG_13\"}");
        int genre = uniqueGenre();
        int rRated = topMovie(genre, "R");
        int unrated = topMovie(genre, null);
        int pg13 = topMovie(genre, "PG-13");
        // The (buggy) ranker returns an R movie, an unrated one and a movie core-api never cached.
        mlReturns(rRated, unrated, 2147000003, pg13);

        mockMvc.perform(get(FOR_YOU).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].movie.id").value(pg13))
                .andExpect(jsonPath("$.items[0].certification").value("PG_13"))
                .andExpect(jsonPath("$.items[0].score").value(8.5))
                .andExpect(jsonPath("$.items[0].explanation").value("Because you like action"));
        MlUserRecommendationRequest request = lastRequest();
        assertThat(request.candidateMovieIds()).contains(pg13).doesNotContain(rRated, unrated);
        assertThat(request.filters().maxCertification()).isEqualTo("PG-13");
        assertThat(request.filters().allowUnrated()).isFalse();
    }

    @Test
    void contentLimitsApplyToTheFallbackToo() throws Exception {
        TestUser user = user();
        setComfort(user, "{\"contentLimits\": {\"VIOLENCE\": \"MILD\"}}");
        int genre = uniqueGenre();
        int violent = topMovie(genre, "PG-13", Map.of("violence", "strong"));
        int gentle = topMovie(genre, "PG", Map.of("violence", "none"));
        List<Integer> ids = forYouIds(user);
        assertThat(ids).contains(gentle).doesNotContain(violent);
    }

    @Test
    void resultsAreCachedUntilTheVectorVersionChanges() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int first = topMovie(genre, "PG");
        int second = topMovie(genre, "PG");
        mlReturns(first, second);

        assertThat(forYouIds(user)).containsExactly(first, second);
        assertThat(forYouIds(user)).containsExactly(first, second);
        verify(mlClient, times(1)).userRecommendations(any());

        // A rating is excluded at once, from the cached list, before any rebuild.
        rate(user, first, 9);
        assertThat(forYouIds(user)).containsExactly(second);
        verify(mlClient, times(1)).userRecommendations(any());

        // A new vector version recomputes (built by the fallback here).
        when(mlClient.isConfigured()).thenReturn(false);
        buildVector(user);
        mlReturns(second);
        assertThat(forYouIds(user)).containsExactly(second);
        verify(mlClient, times(2)).userRecommendations(any());
    }

    @Test
    void aComfortChangeRecomputesTheCachedList() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int pg = topMovie(genre, "PG");
        topMovie(genre, "G"); // so the G pool is not empty and the ranker is asked again
        mlReturns(pg);
        assertThat(forYouIds(user)).containsExactly(pg);
        setComfort(user, "{\"maxCertification\": \"G\"}");
        mlReturns();
        assertThat(forYouIds(user)).isEmpty();
        verify(mlClient, times(2)).userRecommendations(any());
    }

    @Test
    void anMlFailureFallsBackToPopularity() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        int top = topMovie(genre, "PG");
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.userRecommendations(any())).thenThrow(new MlServiceException("down"));
        mockMvc.perform(get(FOR_YOU).with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].movie.id").value(top))
                .andExpect(jsonPath("$.items[0].explanation").value(FallbackRanker.EXPLANATION));
    }

    @Test
    void pagesOfTwentyWithAnOpaqueCursor() throws Exception {
        TestUser user = userWithVector();
        int genre = uniqueGenre();
        Integer[] ids = new Integer[25];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = topMovie(genre, "PG");
        }
        mlReturns(ids);
        String first = json(mockMvc.perform(get(FOR_YOU).with(user.auth()))
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.items[0].movie.id").value(ids[0]))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty()));
        String cursor = JsonPath.read(first, "$.nextCursor");
        mockMvc.perform(get(FOR_YOU).param("cursor", cursor).with(user.auth()))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].movie.id").value(ids[20]))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get(FOR_YOU).param("cursor", "not-a-cursor").with(user.auth())).andExpect(status().isBadRequest());
    }

    @Test
    void dismissIsIdempotentAndMarksTheVectorDirty() throws Exception {
        TestUser user = userWithVector();
        int movie = topMovie(uniqueGenre(), "PG");
        mockMvc.perform(post(FOR_YOU + "/{id}/dismiss", movie).with(user.auth())).andExpect(status().isNoContent());
        assertThat(dirtyReason(user)).isEqualTo("DISMISS");
        buildVector(user);
        mockMvc.perform(post(FOR_YOU + "/{id}/dismiss", movie).with(user.auth())).andExpect(status().isNoContent());
        assertThat(count("rec_dismissals", user.id())).isEqualTo(1);
        // A repeat dismissal is no new signal.
        assertThat(isDirty(user)).isFalse();
        assertThat(forYouIds(user)).doesNotContain(movie);

        mockMvc.perform(post(FOR_YOU + "/{id}/dismiss", 2147000004).with(user.auth())).andExpect(status().isNotFound());
        mockMvc.perform(post(FOR_YOU + "/abc/dismiss").with(user.auth())).andExpect(status().isBadRequest());
    }
}
