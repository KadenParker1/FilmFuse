package com.pm.filmfuse.reco.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the group recommender needs for one ranking.
 *
 * @param memberIds  session members, in a fixed order (the closeness matrix uses it)
 * @param closeness  n x n, {@code closeness[i][j]} = how close member i is to member j (0-1)
 * @param filter     the effective comfort filter (hard)
 * @param criteria   the session's own filters (hard)
 * @param excluded   movies never to return (already shown, vetoed)
 * @param strategy   the ML contract's lowercase strategy value
 * @param k          how many results to return at most
 */
public record GroupRecommendationInput(
        List<UUID> memberIds,
        double[][] closeness,
        ContentFilter filter,
        CandidateCriteria criteria,
        Set<Integer> excluded,
        String strategy,
        int k) {

    public GroupRecommendationInput {
        memberIds = List.copyOf(memberIds);
        excluded = Set.copyOf(excluded);
    }
}
