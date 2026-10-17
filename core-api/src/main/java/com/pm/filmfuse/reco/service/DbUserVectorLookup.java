package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.model.UserVectorLookup;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The latest built vector of each member (a dirty row still has its previous vector, which is used:
 * sessions always take the latest stored version). Members with no built vector are absent, so
 * they are snapshotted with {@code hasVector = false} and sent with {@code vector: null}.
 */
@Component
public class DbUserVectorLookup implements UserVectorLookup {

    private final UserVectorRepository vectors;

    public DbUserVectorLookup(UserVectorRepository vectors) {
        this.vectors = vectors;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, MlUserVector> vectorsOf(Collection<UUID> userIds) {
        return vectors.builtVectorsOf(userIds);
    }
}
