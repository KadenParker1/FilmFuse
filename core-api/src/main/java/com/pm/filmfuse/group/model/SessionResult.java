package com.pm.filmfuse.group.model;

import com.pm.filmfuse.reco.dto.Certification;
import java.util.Map;
import java.util.UUID;

/** A row of {@code app.rec_session_results}. */
public record SessionResult(
        int movieId,
        int rank,
        double groupScore,
        Map<UUID, Double> memberScores,
        String explanation,
        Certification certification) {
}
