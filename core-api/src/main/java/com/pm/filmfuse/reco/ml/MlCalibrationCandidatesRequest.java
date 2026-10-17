package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.UUID;

/**
 * ML contract {@code CalibrationCandidatesRequest} ({@code POST /internal/calibration-candidates}).
 * {@code seenMovieIds}: everything the user already rated, marked unseen or picked as a favourite.
 */
public record MlCalibrationCandidatesRequest(UUID userId, List<Integer> seenMovieIds, int k) {
}
