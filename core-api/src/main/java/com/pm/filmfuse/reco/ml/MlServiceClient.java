package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for python-ml-service's internal API (contract: python-ml-service
 * {@code app/models/contract.py}). Every request carries {@code X-Internal-Key}. Any failure - an
 * error status, a timeout (5 s read, see {@link MlConfig}), a refused connection or an unreadable
 * body - is an {@link MlServiceException}; callers fall back rather than fail the user's request.
 */
public class MlServiceClient {

    public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    static final String GROUP_RECOMMENDATIONS = "/internal/group-recommendations";
    static final String USER_VECTORS = "/internal/user-vectors";
    static final String USER_RECOMMENDATIONS = "/internal/user-recommendations";
    static final String CALIBRATION_CANDIDATES = "/internal/calibration-candidates";

    private final RestClient restClient;
    private final boolean configured;

    /**
     * @param builder a builder already carrying the request factory (timeouts); tests bind a
     *                {@code MockRestServiceServer} to it instead
     */
    public MlServiceClient(RestClient.Builder builder, MlProperties properties) {
        this.configured = properties.isConfigured();
        if (configured) {
            builder.baseUrl(properties.baseUrl());
        }
        if (!properties.internalKey().isBlank()) {
            builder.defaultHeader(INTERNAL_KEY_HEADER, properties.internalKey());
        }
        this.restClient = builder
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw new MlServiceException("ML service " + request.getURI().getPath() + " returned "
                            + response.getStatusCode().value());
                })
                .build();
    }

    /** False when {@code ML_SERVICE_URL} is blank: callers should not call at all. */
    public boolean isConfigured() {
        return configured;
    }

    /** {@code POST /internal/group-recommendations}; the results in the ML service's order. */
    public List<MlGroupRecommendation> groupRecommendations(MlGroupRecommendationRequest request) {
        if (!configured) {
            throw new MlServiceException("ML_SERVICE_URL is not set");
        }
        MlGroupRecommendationResponse response = call(() -> restClient.post()
                .uri(GROUP_RECOMMENDATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MlGroupRecommendationResponse.class));
        if (response == null || response.results() == null) {
            throw new MlServiceException("ML service returned no results body");
        }
        return response.results();
    }

    /** {@code POST /internal/user-vectors}; a response without an interpretable vector is an {@link MlServiceException}. */
    public MlUserVectorResponse userVector(MlUserVectorRequest request) {
        MlUserVectorResponse response = post(USER_VECTORS, request, MlUserVectorResponse.class);
        if (response == null || response.vector() == null || response.vector().interpretable() == null
                || response.vector().interpretable().isEmpty() || response.modelVersion() == null
                || response.modelVersion().isBlank()) {
            throw new MlServiceException("ML service returned no usable vector");
        }
        return response;
    }

    /** {@code POST /internal/user-recommendations}; the results in the ML service's order. */
    public List<MlUserRecommendation> userRecommendations(MlUserRecommendationRequest request) {
        MlUserRecommendationResponse response = post(USER_RECOMMENDATIONS, request, MlUserRecommendationResponse.class);
        if (response == null || response.results() == null) {
            throw new MlServiceException("ML service returned no results body");
        }
        return response.results();
    }

    /** {@code POST /internal/calibration-candidates}; movie ids in the ML service's order. */
    public List<Integer> calibrationCandidates(MlCalibrationCandidatesRequest request) {
        MlCalibrationCandidatesResponse response = post(CALIBRATION_CANDIDATES, request,
                MlCalibrationCandidatesResponse.class);
        if (response == null || response.movieIds() == null) {
            throw new MlServiceException("ML service returned no movieIds body");
        }
        return response.movieIds();
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        if (!configured) {
            throw new MlServiceException("ML_SERVICE_URL is not set");
        }
        return call(() -> restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(responseType));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            throw new MlServiceException("ML service call failed", ex);
        }
    }
}
