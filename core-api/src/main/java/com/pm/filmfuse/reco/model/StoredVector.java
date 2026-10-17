package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.dto.VectorStatus;
import com.pm.filmfuse.reco.ml.MlUserVector;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A {@code user_interest_vectors} row. {@code version} 0 = no vector built yet (the row exists only
 * because a taste write marked it dirty); {@code interpretable} is then null.
 */
public record StoredVector(
        UUID userId,
        List<Double> interpretable,
        List<Double> latent,
        Integer dim,
        String modelVersion,
        int version,
        boolean dirty,
        VectorChangeReason dirtyReason,
        Instant dirtySince,
        Instant updatedAt) {

    public boolean isBuilt() {
        return version > 0 && interpretable != null;
    }

    /** The vector as the ML contract sends it; null when none was built yet. */
    public MlUserVector toMl() {
        return isBuilt() ? new MlUserVector(interpretable, latent) : null;
    }

    public VectorStatus status() {
        if (dirty) {
            return VectorStatus.REBUILDING;
        }
        return isBuilt() ? VectorStatus.FRESH : VectorStatus.NONE;
    }
}
