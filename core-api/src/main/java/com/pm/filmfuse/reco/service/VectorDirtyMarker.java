package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every taste write calls this <strong>inside its own transaction</strong> (enforced: propagation
 * MANDATORY), so the signal and the dirty mark commit or roll back together. The rebuild itself is
 * {@link VectorRefreshScheduler}'s job.
 */
@Component
public class VectorDirtyMarker {

    private final UserVectorRepository vectors;
    private final Clock clock;

    public VectorDirtyMarker(UserVectorRepository vectors, Clock clock) {
        this.vectors = vectors;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markDirty(UUID userId, VectorChangeReason reason) {
        vectors.markDirty(userId, reason, clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markDirty(Collection<UUID> userIds, VectorChangeReason reason) {
        userIds.forEach(id -> markDirty(id, reason));
    }
}
