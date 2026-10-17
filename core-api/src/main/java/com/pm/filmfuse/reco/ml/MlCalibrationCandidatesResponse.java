package com.pm.filmfuse.reco.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** ML contract {@code CalibrationCandidatesResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlCalibrationCandidatesResponse(List<Integer> movieIds) {
}
