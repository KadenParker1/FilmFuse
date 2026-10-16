package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ML contract {@code GroupRecommendationResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlGroupRecommendationResponse(List<MlGroupRecommendation> results) {
}
