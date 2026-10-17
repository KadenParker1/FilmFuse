package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds dirty interest vectors in the background: every {@code interval} (15 s) it takes the
 * users whose latest taste write is at least {@code debounce} (10 s) old and rebuilds each through
 * {@link VectorBuildService}. Single replica only - nothing here is distributed-locked. Disabled
 * (no {@code @EnableScheduling}) when {@code filmfuse.vectors.refresh.enabled=false}, as under the
 * test profile, where tests call {@link #refreshDue()} directly with the test clock.
 */
@Component
public class VectorRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(VectorRefreshScheduler.class);

    private final UserVectorRepository vectors;
    private final VectorBuildService builder;
    private final VectorRefreshProperties properties;
    private final Clock clock;

    public VectorRefreshScheduler(UserVectorRepository vectors, VectorBuildService builder,
                                  VectorRefreshProperties properties, Clock clock) {
        this.vectors = vectors;
        this.builder = builder;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${filmfuse.vectors.refresh.interval:PT15S}",
            initialDelayString = "${filmfuse.vectors.refresh.interval:PT15S}")
    void tick() {
        refreshDue();
    }

    /**
     * One pass: rebuilds every user dirty for at least the debounce (up to {@code batchSize}). One
     * user's failure never stops the others.
     *
     * @return how many new versions were stored
     */
    public int refreshDue() {
        List<UUID> due = vectors.findDue(clock.instant().minus(properties.debounce()), properties.batchSize());
        int built = 0;
        for (UUID userId : due) {
            try {
                if (builder.rebuildIfDirty(userId).isPresent()) {
                    built++;
                }
            } catch (RuntimeException ex) {
                log.error("Vector rebuild failed for {}", userId, ex);
            }
        }
        if (!due.isEmpty()) {
            log.info("Vector refresh: {} due, {} rebuilt", due.size(), built);
        }
        return built;
    }
}
