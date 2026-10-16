package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.dto.Certification;
import java.util.Map;
import java.util.UUID;

/**
 * One recommendation as stored in a session: the ranker's scores and explanation, plus the movie's
 * certification at that moment (null = unknown).
 */
public record RankedMovie(
        int movieId,
        double groupScore,
        Map<UUID, Double> memberScores,
        String explanation,
        Certification certification) {

    public RankedMovie {
        memberScores = memberScores == null ? Map.of() : Map.copyOf(memberScores);
        explanation = explanation == null ? "" : explanation;
    }
}
