package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** ML contract {@code UserRecommendation}; also what {@link com.pm.filmfuse.reco.service.FallbackRanker} returns for one user. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlUserRecommendation(int movieId, double score, String explanation) {
}
