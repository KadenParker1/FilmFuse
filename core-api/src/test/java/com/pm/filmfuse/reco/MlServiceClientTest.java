package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.ml.MlCalibrationCandidatesRequest;
import com.pm.filmfuse.reco.ml.MlFavorite;
import com.pm.filmfuse.reco.ml.MlFilters;
import com.pm.filmfuse.reco.ml.MlGenrePref;
import com.pm.filmfuse.reco.ml.MlMoodPref;
import com.pm.filmfuse.reco.ml.MlRating;
import com.pm.filmfuse.reco.ml.MlUserRecommendation;
import com.pm.filmfuse.reco.ml.MlUserRecommendationRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.ml.MlGroupMember;
import com.pm.filmfuse.reco.ml.MlGroupRecommendation;
import com.pm.filmfuse.reco.ml.MlGroupRecommendationRequest;
import com.pm.filmfuse.reco.ml.MlProperties;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserVector;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The wire format sent to python-ml-service (app/models/contract.py) and how failures surface. */
class MlServiceClientTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String URL = "http://ml.internal:8001/internal/group-recommendations";

    private MockRestServiceServer server;

    private MlServiceClient client(String baseUrl, String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new MlServiceClient(builder, new MlProperties(baseUrl, key));
    }

    private static MlGroupRecommendationRequest request() {
        return new MlGroupRecommendationRequest(
                List.of(new MlGroupMember(A, null, 1.0),
                        new MlGroupMember(B, new MlUserVector(List.of(7.0, 3.5), null), 1.0)),
                List.of(List.of(0.0, 0.8), List.of(0.5, 0.0)),
                new MlFilters(120, 1990, List.of(35, 18), "PG-13", Map.of("violence", "mild", "sexual", "none"), false),
                List.of(13),
                List.of(603, 550, 278),
                "least_misery",
                20);
    }

    @Test
    void sendsTheContractShapeInCamelCaseWithTheInternalKey() {
        MlServiceClient client = client("http://ml.internal:8001/", "secret");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(MlServiceClient.INTERNAL_KEY_HEADER, "secret"))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().json("""
                        {
                          "members": [
                            {"userId": "11111111-1111-1111-1111-111111111111", "vector": null, "weight": 1.0},
                            {"userId": "22222222-2222-2222-2222-222222222222",
                             "vector": {"interpretable": [7.0, 3.5], "latent": null}, "weight": 1.0}
                          ],
                          "closeness": [[0.0, 0.8], [0.5, 0.0]],
                          "filters": {
                            "maxRuntime": 120,
                            "minYear": 1990,
                            "genreIds": [35, 18],
                            "maxCertification": "PG-13",
                            "contentLimits": {"violence": "mild", "sexual": "none"},
                            "allowUnrated": false
                          },
                          "excludeMovieIds": [13],
                          "candidateMovieIds": [603, 550, 278],
                          "strategy": "least_misery",
                          "k": 20
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"results": [
                          {"movieId": 603, "groupScore": 8.25,
                           "memberScores": {"11111111-1111-1111-1111-111111111111": 9.0,
                                            "22222222-2222-2222-2222-222222222222": 7.5},
                           "explanation": "You both like sci-fi", "extra": "ignored"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<MlGroupRecommendation> results = client.groupRecommendations(request());

        server.verify();
        assertThat(results).containsExactly(new MlGroupRecommendation(603, 8.25, Map.of(A, 9.0, B, 7.5),
                "You both like sci-fi"));
        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    void everyStrategyIsSentAsTheContractsLowercaseValue() {
        for (com.pm.filmfuse.group.dto.Strategy strategy : com.pm.filmfuse.group.dto.Strategy.values()) {
            assertThat(strategy.mlValue()).isEqualTo(strategy.name().toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(List.of(com.pm.filmfuse.group.dto.Strategy.values()))
                .extracting(com.pm.filmfuse.group.dto.Strategy::mlValue)
                .containsExactly("mean", "least_misery", "most_pleasure", "closeness_weighted", "fairness");
    }

    @Test
    void serverErrorsAreMlServiceExceptions() {
        MlServiceClient client = client("http://ml.internal:8001", "secret");
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client.groupRecommendations(request())).isInstanceOf(MlServiceException.class)
                .hasMessageContaining("500");
    }

    @Test
    void clientErrorsAreMlServiceExceptions() {
        MlServiceClient client = client("http://ml.internal:8001", "wrong");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.groupRecommendations(request())).isInstanceOf(MlServiceException.class);

        MlServiceClient again = client("http://ml.internal:8001", "secret");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> again.groupRecommendations(request())).isInstanceOf(MlServiceException.class);
    }

    @Test
    void timeoutsAreMlServiceExceptions() {
        MlServiceClient client = client("http://ml.internal:8001", "secret");
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> client.groupRecommendations(request())).isInstanceOf(MlServiceException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void unreadableOrEmptyBodiesAreMlServiceExceptions() {
        MlServiceClient client = client("http://ml.internal:8001", "secret");
        server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.groupRecommendations(request())).isInstanceOf(MlServiceException.class);

        MlServiceClient again = client("http://ml.internal:8001", "secret");
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> again.groupRecommendations(request())).isInstanceOf(MlServiceException.class);
    }

    @Test
    void aBlankUrlIsNotConfiguredAndNeverCalls() {
        MlServiceClient client = client("  ", "secret");
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.groupRecommendations(request())).isInstanceOf(MlServiceException.class);
        server.verify();
        assertThat(new MlProperties(null, null).isConfigured()).isFalse();
    }

    // ------------------------------------------------------------------------------- Phase 1b endpoints

    private static final String BASE = "http://ml.internal:8001";

    @Test
    void userVectorsSendsTheContractShape() {
        MlServiceClient client = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/user-vectors"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(MlServiceClient.INTERNAL_KEY_HEADER, "secret"))
                .andExpect(content().json("""
                        {
                          "userId": "11111111-1111-1111-1111-111111111111",
                          "previousVector": {"interpretable": [5.0, 6.0], "latent": [0.1]},
                          "genrePrefs": [{"genreId": 28, "score": 9}],
                          "moodPrefs": [{"moodTag": "cozy", "score": 7}],
                          "favorites": [{"movieId": 603, "rank": 1}],
                          "ratings": [{"movieId": 550, "score": 8, "source": "post_watch",
                                       "createdAt": "2026-10-16T09:30:00.123456Z"}],
                          "unseen": [13],
                          "dismissals": [278]
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"vector": {"interpretable": [9.0, 4.5], "latent": null}, "dim": 2, "modelVersion": "m-1", "x": 1}
                        """, MediaType.APPLICATION_JSON));

        MlUserVectorResponse response = client.userVector(new MlUserVectorRequest(A,
                new MlUserVector(List.of(5.0, 6.0), List.of(0.1)),
                List.of(new MlGenrePref(28, 9)), List.of(new MlMoodPref("cozy", 7)), List.of(new MlFavorite(603, 1)),
                List.of(new MlRating(550, 8, "post_watch", "2026-10-16T09:30:00.123456Z")), List.of(13), List.of(278)));

        server.verify();
        assertThat(response).isEqualTo(new MlUserVectorResponse(new MlUserVector(List.of(9.0, 4.5), null), 2, "m-1"));
    }

    @Test
    void aNewUserIsSentWithANullPreviousVector() {
        MlServiceClient client = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/user-vectors"))
                .andExpect(content().json("""
                        {"userId": "11111111-1111-1111-1111-111111111111", "previousVector": null, "genrePrefs": [],
                         "moodPrefs": [], "favorites": [], "ratings": [], "unseen": [], "dismissals": []}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"vector": {"interpretable": [5.0]}, "dim": 1, "modelVersion": "m-1"}
                        """, MediaType.APPLICATION_JSON));
        client.userVector(new MlUserVectorRequest(A, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        server.verify();
    }

    @Test
    void aVectorResponseWithoutAVectorOrModelVersionIsAnMlServiceException() {
        MlUserVectorRequest request = new MlUserVectorRequest(A, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
        for (String body : List.of("{}", "{\"vector\": {\"interpretable\": []}, \"dim\": 0, \"modelVersion\": \"m\"}",
                "{\"vector\": {\"interpretable\": [1.0]}, \"dim\": 1}")) {
            MlServiceClient client = client(BASE, "secret");
            server.expect(requestTo(BASE + "/internal/user-vectors")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.userVector(request)).isInstanceOf(MlServiceException.class);
        }
        MlServiceClient failing = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/user-vectors")).andRespond(withServerError());
        assertThatThrownBy(() -> failing.userVector(request)).isInstanceOf(MlServiceException.class);
    }

    @Test
    void userRecommendationsSendsTheContractShape() {
        MlServiceClient client = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/user-recommendations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "userId": "11111111-1111-1111-1111-111111111111",
                          "vector": {"interpretable": [7.0], "latent": null},
                          "filters": {"maxRuntime": null, "minYear": null, "genreIds": null, "maxCertification": "PG-13",
                                      "contentLimits": {"violence": "mild"}, "allowUnrated": false},
                          "excludeMovieIds": [13],
                          "candidateMovieIds": [603, 550],
                          "k": 100
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"results": [{"movieId": 603, "score": 8.5, "explanation": "Sci-fi fan"}]}
                        """, MediaType.APPLICATION_JSON));

        List<MlUserRecommendation> results = client.userRecommendations(new MlUserRecommendationRequest(A,
                new MlUserVector(List.of(7.0), null),
                new MlFilters(null, null, null, "PG-13", Map.of("violence", "mild"), false),
                List.of(13), List.of(603, 550), 100));

        server.verify();
        assertThat(results).containsExactly(new MlUserRecommendation(603, 8.5, "Sci-fi fan"));
    }

    @Test
    void calibrationCandidatesSendsTheContractShape() {
        MlServiceClient client = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/calibration-candidates"))
                .andExpect(content().json("""
                        {"userId": "11111111-1111-1111-1111-111111111111", "seenMovieIds": [13, 603], "k": 10}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"movieIds\": [278, 238]}", MediaType.APPLICATION_JSON));
        assertThat(client.calibrationCandidates(new MlCalibrationCandidatesRequest(A, List.of(13, 603), 10)))
                .containsExactly(278, 238);
        server.verify();
    }

    @Test
    void phase1bEndpointsFailLikeTheGroupOne() {
        MlServiceClient client = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/user-recommendations")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.userRecommendations(new MlUserRecommendationRequest(A,
                new MlUserVector(List.of(1.0), null), null, List.of(), null, 5))).isInstanceOf(MlServiceException.class);

        MlServiceClient again = client(BASE, "secret");
        server.expect(requestTo(BASE + "/internal/calibration-candidates"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> again.calibrationCandidates(new MlCalibrationCandidatesRequest(A, List.of(), 5)))
                .isInstanceOf(MlServiceException.class);

        MlServiceClient unconfigured = client("", "secret");
        assertThatThrownBy(() -> unconfigured.calibrationCandidates(new MlCalibrationCandidatesRequest(A, List.of(), 5)))
                .isInstanceOf(MlServiceException.class);
        assertThatThrownBy(() -> unconfigured.userVector(new MlUserVectorRequest(A, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()))).isInstanceOf(MlServiceException.class);
        server.verify();
    }
}
