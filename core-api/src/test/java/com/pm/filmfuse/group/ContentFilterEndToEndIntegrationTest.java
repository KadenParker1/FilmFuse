package com.pm.filmfuse.group;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.ml.MlGroupRecommendation;
import com.pm.filmfuse.reco.ml.MlGroupRecommendationRequest;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.service.FallbackRanker;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comfort filters end to end: whatever the ranker returns, a session never shows a movie above its
 * effective filter. The ML client is stubbed to return over-limit movies on purpose.
 */
class ContentFilterEndToEndIntegrationTest extends AbstractGroupIntegrationTest {

    private static final String NO_LIMITS = "{\"maxCertification\": null, \"contentLimits\": {}, \"allowUnrated\": true}";

    /** Makes the ML client "configured" and return {@code movieIds} in that order. */
    private void mlReturns(Integer... movieIds) {
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.groupRecommendations(any())).thenReturn(Arrays.stream(movieIds)
                .map(id -> new MlGroupRecommendation(id, 8.5, Map.of(), "Because the ranker said so"))
                .toList());
    }

    private MlGroupRecommendationRequest sentToMl() {
        ArgumentCaptor<MlGroupRecommendationRequest> captor = ArgumentCaptor.forClass(MlGroupRecommendationRequest.class);
        verify(mlClient).groupRecommendations(captor.capture());
        return captor.getValue();
    }

    @Test
    void aPg13MemberNeverGetsAnRMovieEvenWhenTheRankerReturnsOne() throws Exception {
        TestUser strict = user();
        TestUser relaxed = user();
        befriend(strict, relaxed);
        setComfort(strict, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {}, \"allowUnrated\": true}");
        setComfort(relaxed, NO_LIMITS);
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 50);
        int nc17 = movie(genre, "NC-17", 40);
        int pg13 = movie(genre, "PG-13", 30);
        int g = movie(genre, "G", 20);
        mlReturns(rated, pg13, nc17, g);

        String body = json(createAdHocSession(relaxed, genre, strict)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG_13"))
                .andExpect(jsonPath("$.effectiveFilter.label").value("Up to PG-13"))
                .andExpect(jsonPath("$.results[0].explanation").value("Because the ranker said so"))
                .andExpect(jsonPath("$.results[0].groupScore").value(8.5)));

        assertThat(resultIds(body)).containsExactly(pg13, g).doesNotContain(rated, nc17);
        MlGroupRecommendationRequest request = sentToMl();
        assertThat(request.candidateMovieIds()).containsExactly(pg13, g);
        assertThat(request.filters().maxCertification()).isEqualTo("PG-13");
        assertThat(request.filters().allowUnrated()).isTrue();
    }

    @Test
    void theFallbackRankerOnlySeesTheEligiblePoolToo() throws Exception {
        TestUser strict = user();
        TestUser relaxed = user();
        befriend(strict, relaxed);
        setComfort(strict, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {}, \"allowUnrated\": true}");
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 50);
        int pg13 = movie(genre, "PG-13", 30);

        String body = json(createAdHocSession(relaxed, genre, strict).andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].explanation").value(FallbackRanker.EXPLANATION)));

        assertThat(resultIds(body)).containsExactly(pg13).doesNotContain(rated);
        verify(mlClient, never()).groupRecommendations(any());
    }

    @Test
    void aKidsGroupCappedAtPgNeverGetsPg13() throws Exception {
        TestUser parent = user();
        TestUser kid = user();
        UUID groupId = activeGroup(parent, kid);
        setComfort(parent, NO_LIMITS);
        setComfort(kid, NO_LIMITS);
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(parent.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comfortCap\": {\"maxCertification\": \"PG\"}}"))
                .andExpect(status().isOk());
        int genre = uniqueGenre();
        int pg13 = movie(genre, "PG-13", 90);
        int rated = movie(genre, "R", 80);
        int pg = movie(genre, "PG", 10);
        int g = movie(genre, "G", 5);
        int unrated = movie(genre, null, 70);
        mlReturns(pg13, rated, unrated, pg, g);

        String body = json(createGroupSession(kid, groupId, genre)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG"))
                // Both members allow unrated content, and a cap cannot take that away.
                .andExpect(jsonPath("$.effectiveFilter.allowUnrated").value(true)));

        assertThat(resultIds(body)).containsExactly(unrated, pg, g).doesNotContain(pg13, rated);
        assertThat(JsonPath.<List<String>>read(body, "$.results[*].certification")).containsExactly(null, "PG", "G");
    }

    @Test
    void unknownCertificationIsExcludedUnlessEveryMemberAllowsUnrated() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        setComfort(a, "{\"maxCertification\": \"R\", \"contentLimits\": {}, \"allowUnrated\": true}");
        setComfort(b, "{\"maxCertification\": \"R\", \"contentLimits\": {}, \"allowUnrated\": false}");
        int genre = uniqueGenre();
        int unrated = movie(genre, null, 50);
        int rated = movie(genre, "R", 10);
        mlReturns(unrated, rated);

        String strictBody = json(createAdHocSession(a, genre, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.allowUnrated").value(false)));
        assertThat(resultIds(strictBody)).containsExactly(rated);

        setComfort(b, "{\"maxCertification\": \"R\", \"contentLimits\": {}, \"allowUnrated\": true}");
        String relaxedBody = json(createAdHocSession(a, genre, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.allowUnrated").value(true)));
        assertThat(resultIds(relaxedBody)).containsExactly(unrated, rated);
    }

    @Test
    void withoutACertificationLimitUnknownCertificationIsFine() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int unrated = movie(genre, null, 50);
        int nc17 = movie(genre, "NC-17", 10);

        // Default settings: no limits, allowUnrated false - nothing to be unrated against.
        String body = json(createAdHocSession(a, genre, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.label").value("No limits")));
        assertThat(resultIds(body)).containsExactly(unrated, nc17);
    }

    @Test
    void aMildViolenceLimitExcludesStrongViolenceAndUnknownViolence() throws Exception {
        TestUser careful = user();
        TestUser other = user();
        befriend(careful, other);
        setComfort(careful, "{\"maxCertification\": null, \"contentLimits\": {\"VIOLENCE\": \"MILD\"}, \"allowUnrated\": false}");
        setComfort(other, NO_LIMITS);
        int genre = uniqueGenre();
        int strong = movie(genre, "PG-13", 90, Map.of("violence", "strong"));
        int moderate = movie(genre, "PG-13", 80, Map.of("violence", "moderate"));
        int unknown = movie(genre, "PG-13", 70, Map.of("sexual", "none"));
        int mild = movie(genre, "PG-13", 60, Map.of("violence", "mild"));
        int none = movie(genre, "R", 50, Map.of("violence", "none", "language", "strong"));
        mlReturns(strong, moderate, unknown, mild, none);

        String body = json(createAdHocSession(other, genre, careful).andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.contentLimits.VIOLENCE").value("MILD"))
                .andExpect(jsonPath("$.effectiveFilter.label").value("Mild violence")));
        assertThat(resultIds(body)).containsExactly(mild, none);

        MlGroupRecommendationRequest request = sentToMl();
        assertThat(request.filters().contentLimits()).containsExactly(Map.entry("violence", "mild"));
        assertThat(request.candidateMovieIds()).containsExactly(mild, none);

        // Allowing unrated content lets the unknown one through, never the over-limit ones.
        setComfort(careful, "{\"maxCertification\": null, \"contentLimits\": {\"VIOLENCE\": \"MILD\"}, \"allowUnrated\": true}");
        setComfort(other, NO_LIMITS);
        String relaxed = json(createAdHocSession(other, genre, careful).andExpect(status().isCreated()));
        assertThat(resultIds(relaxed)).containsExactly(unknown, mild, none);
    }

    @Test
    void everyDimensionLimitIsEnforced() throws Exception {
        for (String dimension : List.of("violence", "sexual", "language", "drugs", "frightening")) {
            TestUser a = user();
            TestUser b = user();
            befriend(a, b);
            setComfort(a, "{\"contentLimits\": {\"" + dimension.toUpperCase() + "\": \"NONE\"}, \"allowUnrated\": true}");
            int genre = uniqueGenre();
            int over = movie(genre, "PG", 20, Map.of(dimension, "mild"));
            int ok = movie(genre, "PG", 10, Map.of(dimension, "none"));
            mlReturns(over, ok);

            String body = json(createAdHocSession(b, genre, a).andExpect(status().isCreated()));
            assertThat(resultIds(body)).as(dimension).containsExactly(ok);
        }
    }

    @Test
    void limitsCombineMostRestrictivelyAcrossMembersAndTheGroupCap() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();
        UUID groupId = activeGroup(a, b, c);
        setComfort(a, "{\"maxCertification\": \"R\", \"contentLimits\": {\"VIOLENCE\": \"MODERATE\"}, \"allowUnrated\": true}");
        setComfort(b, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {\"LANGUAGE\": \"MILD\"}, \"allowUnrated\": true}");
        setComfort(c, "{\"maxCertification\": null, \"contentLimits\": {\"VIOLENCE\": \"STRONG\", \"SEXUAL\": \"NONE\"}, \"allowUnrated\": true}");
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comfortCap\": {\"contentLimits\": {\"VIOLENCE\": \"MILD\"}}}"))
                .andExpect(status().isOk());
        int genre = uniqueGenre();
        movie(genre, "PG", 1);

        createGroupSession(c, groupId, genre)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG_13"))
                .andExpect(jsonPath("$.effectiveFilter.contentLimits.VIOLENCE").value("MILD"))
                .andExpect(jsonPath("$.effectiveFilter.contentLimits.LANGUAGE").value("MILD"))
                .andExpect(jsonPath("$.effectiveFilter.contentLimits.SEXUAL").value("NONE"))
                .andExpect(jsonPath("$.effectiveFilter.allowUnrated").value(true))
                .andExpect(jsonPath("$.effectiveFilter.label")
                        .value("Up to PG-13 · mild violence · no sexual content · mild language"));
    }

    @Test
    void theRankerCannotSmuggleInUnknownExcludedOrDuplicateMovies() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int first = movie(genre, "PG", 20);
        int second = movie(genre, "PG", 10);
        int otherGenre = movie(uniqueGenre(), "PG", 99);
        mlReturns(999_999_999, first, first, otherGenre, second);

        String body = json(createAdHocSession(a, genre, b).andExpect(status().isCreated()));
        assertThat(resultIds(body)).containsExactly(first, second);
    }

    @Test
    void anMlFailureFallsBackToTheFilteredPool() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        setComfort(a, "{\"maxCertification\": \"PG\", \"contentLimits\": {}, \"allowUnrated\": true}");
        int genre = uniqueGenre();
        int rated = movie(genre, "R", 90);
        int pg = movie(genre, "PG", 10);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.groupRecommendations(any())).thenThrow(new MlServiceException("boom"));

        String body = json(createAdHocSession(a, genre, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].explanation").value(FallbackRanker.EXPLANATION)));
        assertThat(resultIds(body)).containsExactly(pg).doesNotContain(rated);
    }

    @Test
    void theMlRequestCarriesMembersClosenessStrategyAndFilters() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        mlReturns(movie);

        mockMvc.perform(post("/api/v1/sessions").with(a.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + b.id() + "\"], \"strategy\": \"LEAST_MISERY\", "
                                + "\"filters\": {\"maxRuntime\": 150, \"minYear\": 1990, \"genreIds\": [" + genre + "]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.strategy").value("LEAST_MISERY"));

        MlGroupRecommendationRequest request = sentToMl();
        assertThat(request.strategy()).isEqualTo("least_misery");
        assertThat(request.k()).isEqualTo(20);
        assertThat(request.members()).extracting(m -> m.userId()).containsExactly(a.id(), b.id());
        assertThat(request.members()).allSatisfy(m -> {
            assertThat(m.vector()).isNull();
            assertThat(m.weight()).isEqualTo(1.0);
        });
        assertThat(request.closeness()).containsExactly(List.of(0.0, 0.5), List.of(0.5, 0.0));
        assertThat(request.filters().maxRuntime()).isEqualTo(150);
        assertThat(request.filters().minYear()).isEqualTo(1990);
        assertThat(request.filters().genreIds()).containsExactly(genre);
        assertThat(request.filters().maxCertification()).isNull();
        assertThat(request.filters().contentLimits()).isEmpty();
        assertThat(request.excludeMovieIds()).isEmpty();
        assertThat(request.candidateMovieIds()).containsExactly(movie);
    }

    @Test
    void refreshExcludesShownMoviesInTheMlRequest() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int shown = movie(genre, "PG", 2);
        int fresh = movie(genre, "PG", 1);
        int rated = movie(genre, "R", 3);
        setComfort(b, "{\"maxCertification\": \"PG\", \"contentLimits\": {}, \"allowUnrated\": true}");
        mlReturns(shown);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        org.mockito.Mockito.reset(mlClient);
        mlReturns(rated, shown, fresh);
        String body = json(mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(a.auth()))
                .andExpect(status().isOk()));

        assertThat(resultIds(body)).containsExactly(shown, fresh);
        MlGroupRecommendationRequest request = sentToMl();
        assertThat(request.excludeMovieIds()).containsExactly(shown);
        assertThat(request.candidateMovieIds()).containsExactly(fresh);
    }

    @Test
    void keywordDerivedLevelsFromTheCatalogAreEnforced() throws Exception {
        TestUser careful = user();
        TestUser other = user();
        befriend(careful, other);
        setComfort(careful, "{\"contentLimits\": {\"VIOLENCE\": \"MILD\"}, \"allowUnrated\": true}");
        // Fetching details caches Saving Private Ryan (R; "graphic violence" -> strong) and Toy Story (G).
        mockMvc.perform(get("/api/v1/movies/857").with(careful.auth())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/movies/862").with(careful.auth())).andExpect(status().isOk());
        mlReturns(857, 862);

        // Both are in genre 18/16 pools; a session over genres War + Animation sees both.
        String body = json(mockMvc.perform(post("/api/v1/sessions").with(other.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + careful.id() + "\"], \"filters\": {\"genreIds\": [10752, 16]}}"))
                .andExpect(status().isCreated()));
        assertThat(resultIds(body)).contains(862).doesNotContain(857);
    }
}
