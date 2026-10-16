package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.ml.MlUserVector;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Finds members' interest vectors for the ranker. Phase 3 has no {@code user_interest_vectors}
 * table yet, so the only implementation ({@link com.pm.filmfuse.reco.service.NoUserVectorLookup})
 * returns nothing: every session member is snapshotted with {@code hasVector = false} and sent to
 * the ML service with {@code vector: null}. Phase 1b replaces it with a lookup of non-dirty rows in
 * {@code user_interest_vectors} - nothing else needs to change.
 */
public interface UserVectorLookup {

    /** The vectors that exist, keyed by user id; users without one are absent. */
    Map<UUID, MlUserVector> vectorsOf(Collection<UUID> userIds);
}
