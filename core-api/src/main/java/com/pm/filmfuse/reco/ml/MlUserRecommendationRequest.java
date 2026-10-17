package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.UUID;

/**
 * ML contract {@code UserRecommendationRequest} ({@code POST /internal/user-recommendations}).
 *
 * @param candidateMovieIds core-api's pre-filtered eligible pool; the ranker ranks only within it
 */
public record MlUserRecommendationRequest(
        UUID userId,
        MlUserVector vector,
        MlFilters filters,
        List<Integer> excludeMovieIds,
        List<Integer> candidateMovieIds,
        int k) {
}
