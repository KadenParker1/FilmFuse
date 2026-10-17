package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.dto.VectorChangeReason;
import java.time.Instant;
import java.util.List;

/** A {@code user_interest_vector_history} row (latent omitted: nothing in core-api reads it back). */
public record VectorHistoryEntry(
        int version,
        List<Double> interpretable,
        String modelVersion,
        VectorChangeReason reason,
        Instant createdAt) {
}
