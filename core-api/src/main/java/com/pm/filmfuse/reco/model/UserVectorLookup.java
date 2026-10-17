package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.ml.MlUserVector;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Finds members' interest vectors for the ranker ({@link com.pm.filmfuse.reco.service.DbUserVectorLookup}
 * reads {@code user_interest_vectors}). Members without a built vector are absent: they are
 * snapshotted with {@code hasVector = false} and sent to the ML service with {@code vector: null}.
 */
public interface UserVectorLookup {

    /** The vectors that exist, keyed by user id; users without one are absent. */
    Map<UUID, MlUserVector> vectorsOf(Collection<UUID> userIds);
}
