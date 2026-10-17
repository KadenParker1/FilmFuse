package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ML contract {@code UserRecommendationResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlUserRecommendationResponse(List<MlUserRecommendation> results) {
}
