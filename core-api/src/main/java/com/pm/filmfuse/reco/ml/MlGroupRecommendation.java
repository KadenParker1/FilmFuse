package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.UUID;

/** ML contract {@code GroupRecommendation}; also what {@link com.pm.filmfuse.reco.service.FallbackRanker} returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlGroupRecommendation(
        int movieId,
        double groupScore,
        Map<UUID, Double> memberScores,
        String explanation) {
}
