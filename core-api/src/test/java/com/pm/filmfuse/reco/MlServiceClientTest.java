package com.pm.filmfuse.reco;

import com.pm.filmfuse.reco.ml.MlFilters;
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
}
