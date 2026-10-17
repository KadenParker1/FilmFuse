package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** ML contract {@code UserVectorResponse}; also what {@link com.pm.filmfuse.reco.service.FallbackVectorBuilder} returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlUserVectorResponse(MlUserVector vector, int dim, String modelVersion) {
}
