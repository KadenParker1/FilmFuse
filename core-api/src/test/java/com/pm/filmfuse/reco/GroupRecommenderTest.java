package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.ml.MlProperties;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.GroupRecommendationInput;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.model.RankedMovie;
import com.pm.filmfuse.reco.service.ContentFilterService;
import com.pm.filmfuse.reco.service.FallbackRanker;
import com.pm.filmfuse.reco.service.GroupRecommender;
import com.pm.filmfuse.reco.service.NoUserVectorLookup;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GroupRecommender over a real MlServiceClient on MockRestServiceServer: ML results are used when
 * the service answers, the FallbackRanker when it is blank / failing / slow, and the filters are
 * re-applied to whatever comes back.
 */
class GroupRecommenderTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String URL = "http://ml.test/internal/group-recommendations";
    private static final ContentFilter PG13 = new ContentFilter(Certification.PG_13, Map.of(), false);

    private final ContentFilterService contentFilter = mock(ContentFilterService.class);
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        when(contentFilter.isEligible(any(), any())).thenCallRealMethod();
        when(contentFilter.eligiblePool(any(), any(), anyCollection(), anyInt())).thenReturn(List.of(1, 2, 3));
        when(contentFilter.contentOf(anyCollection())).thenReturn(Map.of(
                1, MovieContent.of(1, Certification.PG, Map.of()),
                2, MovieContent.of(2, Certification.PG_13, Map.of()),
                3, MovieContent.of(3, Certification.G, Map.of()),
                4, MovieContent.of(4, Certification.R, Map.of())));
    }

    private GroupRecommender recommender(String baseUrl) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        MlServiceClient client = new MlServiceClient(builder, new MlProperties(baseUrl, "key"));
        return new GroupRecommender(contentFilter, client, new FallbackRanker(), new NoUserVectorLookup());
    }

    private static GroupRecommendationInput input(int k) {
        return new GroupRecommendationInput(List.of(A, B), new double[][] {{0, 0.5}, {0.5, 0}}, PG13,
                CandidateCriteria.NONE, Set.of(), "mean", k);
    }

    private static void assertFallback(List<RankedMovie> ranked) {
        assertThat(ranked).extracting(RankedMovie::movieId).containsExactly(1, 2, 3);
        assertThat(ranked).allSatisfy(r -> {
            assertThat(r.explanation()).isEqualTo("Popular pick");
            assertThat(r.groupScore()).isEqualTo(5.0);
            assertThat(r.memberScores()).isEqualTo(Map.of(A, 5.0, B, 5.0));
        });
        assertThat(ranked).extracting(RankedMovie::certification)
                .containsExactly(Certification.PG, Certification.PG_13, Certification.G);
    }

    @Test
    void usesTheMlServiceAndRefiltersItsResults() {
        GroupRecommender recommender = recommender("http://ml.test");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.candidateMovieIds").value(org.hamcrest.Matchers.contains(1, 2, 3)))
                .andExpect(jsonPath("$.filters.maxCertification").value("PG-13"))
                .andExpect(jsonPath("$.filters.allowUnrated").value(false))
                .andExpect(jsonPath("$.filters.genreIds").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.members[0].vector").value(org.hamcrest.Matchers.nullValue()))
                .andRespond(withSuccess("""
                        {"results": [
                          {"movieId": 4, "groupScore": 9.9, "memberScores": {}, "explanation": "R!"},
                          {"movieId": 3, "groupScore": 9.0, "memberScores": {"%s": 9.5}, "explanation": "Fun"},
                          {"movieId": 3, "groupScore": 8.0, "memberScores": {}, "explanation": "again"},
                          {"movieId": 77, "groupScore": 7.5, "memberScores": {}, "explanation": "unknown"},
                          {"movieId": 1, "groupScore": 7.0, "memberScores": {}, "explanation": "Classic"}
                        ]}
                        """.formatted(A), MediaType.APPLICATION_JSON));

        List<RankedMovie> ranked = recommender.recommend(input(20));

        server.verify();
        assertThat(ranked).containsExactly(
                new RankedMovie(3, 9.0, Map.of(A, 9.5), "Fun", Certification.G),
                new RankedMovie(1, 7.0, Map.of(), "Classic", Certification.PG));
    }

    @Test
    void stopsAtK() {
        GroupRecommender recommender = recommender("http://ml.test");
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"results": [
                  {"movieId": 1, "groupScore": 1, "memberScores": {}, "explanation": ""},
                  {"movieId": 2, "groupScore": 1, "memberScores": {}, "explanation": ""},
                  {"movieId": 3, "groupScore": 1, "memberScores": {}, "explanation": ""}
                ]}
                """, MediaType.APPLICATION_JSON));
        assertThat(recommender.recommend(input(2))).extracting(RankedMovie::movieId).containsExactly(1, 2);
    }

    @Test
    void fallsBackWhenTheServiceIsNotConfigured() {
        assertFallback(recommender("").recommend(input(20)));
        server.verify();
    }

    @Test
    void fallsBackOnA5xx() {
        GroupRecommender recommender = recommender("http://ml.test");
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertFallback(recommender.recommend(input(20)));
    }

    @Test
    void fallsBackOnATimeout() {
        GroupRecommender recommender = recommender("http://ml.test");
        server.expect(requestTo(URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));
        assertFallback(recommender.recommend(input(20)));
    }

    @Test
    void fallsBackWhenTheServiceIsUnreachable() {
        GroupRecommender recommender = recommender("http://ml.test");
        server.expect(requestTo(URL)).andRespond(withException(new IOException("Connection refused")));
        assertFallback(recommender.recommend(input(20)));
    }

    @Test
    void anEmptyPoolNeverCallsTheService() {
        when(contentFilter.eligiblePool(any(), any(), anyCollection(), anyInt())).thenReturn(List.of());
        GroupRecommender recommender = recommender("http://ml.test");
        assertThat(recommender.recommend(input(20))).isEmpty();
        server.verify();
    }

    @Test
    void theFallbackAlsoRespectsSessionCriteriaOnRecheck() {
        when(contentFilter.contentOf(anyCollection())).thenReturn(Map.of(
                1, new MovieContent(1, Certification.PG, Map.of(), 200, 2000, List.of(18)),
                2, new MovieContent(2, Certification.PG, Map.of(), 90, 2000, List.of(18)),
                3, new MovieContent(3, Certification.PG, Map.of(), 90, 2000, List.of(35))));
        GroupRecommendationInput input = new GroupRecommendationInput(List.of(A), new double[][] {{0}}, PG13,
                new CandidateCriteria(120, null, List.of(18)), Set.of(), "mean", 20);
        assertThat(recommender("").recommend(input)).extracting(RankedMovie::movieId).containsExactly(2);
    }

    @Test
    void excludedMoviesAreNeverReturned() {
        GroupRecommendationInput input = new GroupRecommendationInput(List.of(A), new double[][] {{0}}, PG13,
                CandidateCriteria.NONE, Set.of(2), "mean", 20);
        assertThat(recommender("").recommend(input)).extracting(RankedMovie::movieId).containsExactly(1, 3);
        org.mockito.Mockito.verify(contentFilter).eligiblePool(eq(PG13), eq(CandidateCriteria.NONE), eq(Set.of(2)),
                eq(ContentFilterService.POOL_SIZE));
    }

    @Test
    void theFallbackRankerKeepsPoolOrderAndNeutralScores() {
        List<com.pm.filmfuse.reco.ml.MlGroupRecommendation> ranked = new FallbackRanker().rank(List.of(5, 4, 4, 3), List.of(A), 2);
        assertThat(ranked).extracting(com.pm.filmfuse.reco.ml.MlGroupRecommendation::movieId).containsExactly(5, 4);
        assertThat(ranked.getFirst().memberScores()).isEqualTo(Map.of(A, 5.0));
    }
}
