package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.model.UserVectorLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Until Phase 1b: nobody has an interest vector yet. See {@link UserVectorLookup}. */
@Component
public class NoUserVectorLookup implements UserVectorLookup {

    @Override
    public Map<UUID, MlUserVector> vectorsOf(Collection<UUID> userIds) {
        return Map.of();
    }
}
