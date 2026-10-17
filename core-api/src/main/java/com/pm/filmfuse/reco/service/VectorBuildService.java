package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.model.StoredVector;
import com.pm.filmfuse.reco.model.TasteSignals;
import com.pm.filmfuse.reco.repository.TasteSignalRepository;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds one user's next vector version - plumbing only:
 * <ol>
 *   <li>reads the dirty row (its {@code dirty_since} and reason) and every taste signal;</li>
 *   <li>asks the ML service ({@code POST /internal/user-vectors}) - or, when it is not configured,
 *       the {@link FallbackVectorBuilder}. When the ML service <em>fails</em>: a user who already has a
 *       vector keeps it and stays dirty (retried on the next tick); a user with none gets the
 *       fallback so they are not left without a vector;</li>
 *   <li>stores version + 1 and a history row - only if no concurrent build stored a version since
 *       this one read the row (otherwise this result is discarded) - clearing the dirty flag only if
 *       no newer mark arrived while the ML call ran (UserVectorRepository#storeNewVersion).</li>
 * </ol>
 * The ML call runs outside any transaction.
 */
@Service
public class VectorBuildService {

    private static final Logger log = LoggerFactory.getLogger(VectorBuildService.class);

    private final UserVectorRepository vectors;
    private final TasteSignalRepository signals;
    private final MlServiceClient mlClient;
    private final FallbackVectorBuilder fallback;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public VectorBuildService(UserVectorRepository vectors, TasteSignalRepository signals, MlServiceClient mlClient,
                              FallbackVectorBuilder fallback, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.vectors = vectors;
        this.signals = signals;
        this.mlClient = mlClient;
        this.fallback = fallback;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Rebuilds the user's vector if it is dirty (whatever the debounce - the scheduler applies that).
     *
     * @return the new version, or empty when nothing was stored (not dirty, ML failed and the old
     *         vector was kept, or a concurrent build stored first)
     */
    public Optional<Integer> rebuildIfDirty(UUID userId) {
        record Snapshot(StoredVector row, TasteSignals signals) {
        }
        Snapshot snapshot = readTx.execute(status -> vectors.find(userId)
                .filter(StoredVector::dirty)
                .map(row -> new Snapshot(row, signals.load(userId)))
                .orElse(null));
        if (snapshot == null) {
            return Optional.empty();
        }
        StoredVector row = snapshot.row();
        TasteSignals s = snapshot.signals();
        MlUserVectorRequest request = new MlUserVectorRequest(userId, row.toMl(), s.genrePrefs(), s.moodPrefs(),
                s.favorites(), s.ratings(), s.unseen(), s.dismissals());

        MlUserVectorResponse built = build(request, row);
        if (built == null) {
            return Optional.empty();
        }
        Optional<Integer> version = tx.execute(status -> vectors.storeNewVersion(userId, row.version(), built.vector(),
                built.dim(), built.modelVersion(), row.dirtyReason(), row.dirtySince(), clock.instant()));
        if (version == null || version.isEmpty()) {
            log.debug("Discarded a vector build for {}: a concurrent build stored v{}+ first", userId, row.version() + 1);
            return Optional.empty();
        }
        log.debug("Built vector v{} ({}) for {} ({})", version.get(), built.modelVersion(), userId, row.dirtyReason());
        return version;
    }

    /** The ML response, the fallback, or null to keep the current vector. */
    private MlUserVectorResponse build(MlUserVectorRequest request, StoredVector row) {
        if (!mlClient.isConfigured()) {
            return fallback.build(request);
        }
        try {
            MlUserVectorResponse response = mlClient.userVector(request);
            if (response == null || response.vector() == null || response.vector().interpretable() == null) {
                throw new MlServiceException("ML service returned no vector");
            }
            return response;
        } catch (MlServiceException ex) {
            if (row.isBuilt()) {
                log.warn("ML service unavailable; keeping vector v{} of {} and retrying later: {}", row.version(),
                        row.userId(), ex.getMessage());
                return null;
            }
            log.warn("ML service unavailable; using the fallback vector for {}: {}", row.userId(), ex.getMessage());
            return fallback.build(request);
        }
    }
}
