package com.pm.filmfuse.reco.ml;

import java.util.List;

/**
 * ML contract {@code GroupRecommendationRequest} ({@code POST /internal/group-recommendations}).
 *
 * @param closeness         n x n, same order as {@code members}; {@code closeness[i][j]} is how close
 *                          member i is to member j
 * @param candidateMovieIds core-api's pre-filtered eligible pool; the ranker ranks only within it
 * @param strategy          the contract's lowercase value ("mean", "least_misery", ...)
 */
public record MlGroupRecommendationRequest(
        List<MlGroupMember> members,
        List<List<Double>> closeness,
        MlFilters filters,
        List<Integer> excludeMovieIds,
        List<Integer> candidateMovieIds,
        String strategy,
        int k) {
}
