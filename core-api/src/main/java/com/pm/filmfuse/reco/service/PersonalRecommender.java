package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieService;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.reco.dto.RecommendationDTO;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.ml.MlFilters;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserRecommendation;
import com.pm.filmfuse.reco.ml.MlUserRecommendationRequest;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.model.StoredVector;
import com.pm.filmfuse.reco.repository.DismissalRepository;
import com.pm.filmfuse.reco.repository.TasteSignalRepository;
import com.pm.filmfuse.reco.repository.UserVectorRepository;
import com.pm.filmfuse.user.service.ComfortSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Personal "For you" - plumbing around the ML ranker, no ranking logic of its own:
 * <ol>
 *   <li>candidate pool: cached movies that pass the caller's own comfort settings (as the
 *       effective filter), minus everything they rated, marked unseen or dismissed, and their
 *       favourites;</li>
 *   <li>ranked by the ML service ({@code POST /internal/user-recommendations}, ranking only within
 *       the pool) when it is configured and the caller has a vector; otherwise by the
 *       {@link FallbackRanker} (popularity, "Popular pick");</li>
 *   <li>the result is <strong>always</strong> re-checked against the filter and exclusions.</li>
 * </ol>
 *
 * <p><strong>Cache:</strong> the ranked list (up to {@value #K}) is kept in memory per user, keyed by
 * the vector version and the comfort filter it was computed with; a new vector version (or a comfort
 * change) recomputes it on the next read. Every page read re-applies the current exclusions and
 * filter, so a movie rated or dismissed a moment ago never reappears. In-memory only
 * ({@link RecommendationCache}): single replica, lost on restart, at most {@value #MAX_CACHED_USERS}
 * users (least recently used evicted) and 30 minutes per entry. Cursors are positions in the cached
 * list.
 */
@Service
public class PersonalRecommender {

    private static final Logger log = LoggerFactory.getLogger(PersonalRecommender.class);

    public static final int PAGE_SIZE = 20;
    /** How many ranked movies are cached per user (the ML contract's maximum k). */
    public static final int K = 100;
    static final int MAX_CACHED_USERS = 1_000;
    static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private record CachedList(int vectorVersion, ContentFilter filter, List<MlUserRecommendation> ranked) {
    }

    private final ContentFilterService contentFilter;
    private final ComfortSettingsService comfortSettings;
    private final UserVectorRepository vectors;
    private final TasteSignalRepository signals;
    private final DismissalRepository dismissals;
    private final MlServiceClient mlClient;
    private final FallbackRanker fallbackRanker;
    private final MovieService movieService;
    private final MovieSummaries movieSummaries;
    private final VectorDirtyMarker dirtyMarker;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RecommendationCache<CachedList> cache;

    public PersonalRecommender(ContentFilterService contentFilter, ComfortSettingsService comfortSettings,
                               UserVectorRepository vectors, TasteSignalRepository signals,
                               DismissalRepository dismissals, MlServiceClient mlClient, FallbackRanker fallbackRanker,
                               MovieService movieService, MovieSummaries movieSummaries, VectorDirtyMarker dirtyMarker,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.contentFilter = contentFilter;
        this.comfortSettings = comfortSettings;
        this.vectors = vectors;
        this.signals = signals;
        this.dismissals = dismissals;
        this.mlClient = mlClient;
        this.fallbackRanker = fallbackRanker;
        this.movieService = movieService;
        this.movieSummaries = movieSummaries;
        this.dirtyMarker = dirtyMarker;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.cache = new RecommendationCache<>(MAX_CACHED_USERS, CACHE_TTL, clock);
    }

    /** One page ({@value #PAGE_SIZE}) of "For you", best first. */
    public PageDTO<RecommendationDTO> recommendations(UUID userId, String cursor) {
        int start = startOf(cursor);
        ContentFilter filter = ContentFilterService.effective(List.of(comfortSettings.comfortOf(userId)), null);
        StoredVector vector = vectors.find(userId).filter(StoredVector::isBuilt).orElse(null);
        int version = vector == null ? 0 : vector.version();
        Set<Integer> excluded = signals.forYouExclusions(userId);

        CachedList cached = cache.get(userId);
        if (cached == null || cached.vectorVersion() != version || !cached.filter().equals(filter)) {
            cached = new CachedList(version, filter, rank(userId, vector, filter, excluded));
            cache.put(userId, cached);
        }

        List<MlUserRecommendation> ranked = cached.ranked();
        Map<Integer, MovieContent> content = contentFilter.contentOf(
                ranked.subList(Math.min(start, ranked.size()), ranked.size()).stream()
                        .map(MlUserRecommendation::movieId).toList());
        List<MlUserRecommendation> page = new ArrayList<>();
        int index = start;
        boolean more = false;
        for (; index < ranked.size(); index++) {
            MlUserRecommendation candidate = ranked.get(index);
            MovieContent movie = content.get(candidate.movieId());
            if (excluded.contains(candidate.movieId()) || movie == null || !filter.isEligible(movie)) {
                continue;
            }
            if (page.size() == PAGE_SIZE) {
                more = true;
                break;
            }
            page.add(candidate);
        }
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(page.stream().map(MlUserRecommendation::movieId).toList());
        List<RecommendationDTO> items = page.stream()
                .filter(r -> movies.containsKey(r.movieId()))
                .map(r -> new RecommendationDTO(movies.get(r.movieId()), content.get(r.movieId()).certification(),
                        r.score(), r.explanation() == null ? "" : r.explanation()))
                .toList();
        return new PageDTO<>(items, more ? CursorCodec.encode(List.of(String.valueOf(index))) : null);
    }

    /** "Not interested": never recommended again; a weak negative signal (vector dirty, DISMISS). 204, idempotent. */
    public void dismiss(UUID userId, int movieId) {
        movieService.ensureCached(List.of(movieId));
        tx.executeWithoutResult(status -> {
            if (dismissals.insert(userId, movieId, clock.instant())) {
                dirtyMarker.markDirty(userId, VectorChangeReason.DISMISS);
            }
        });
    }

    /**
     * Undo of "Not interested": the movie may be recommended again. Removing a dismissal changes the
     * taste signals too (vector dirty, DISMISS). The cached list was ranked without the movie, so it is
     * dropped. 204, idempotent; a movie that was never dismissed is a no-op.
     */
    public void undismiss(UUID userId, int movieId) {
        tx.executeWithoutResult(status -> {
            if (dismissals.delete(userId, movieId)) {
                dirtyMarker.markDirty(userId, VectorChangeReason.DISMISS);
            }
        });
        cache.remove(userId);
    }

    private List<MlUserRecommendation> rank(UUID userId, StoredVector vector, ContentFilter filter,
                                            Set<Integer> excluded) {
        List<Integer> pool = contentFilter.eligiblePool(filter, CandidateCriteria.NONE, excluded,
                ContentFilterService.POOL_SIZE);
        if (pool.isEmpty()) {
            return List.of();
        }
        List<MlUserRecommendation> ranked;
        if (vector == null || !mlClient.isConfigured()) {
            ranked = fallbackRanker.rankForUser(pool, K);
        } else {
            try {
                ranked = mlClient.userRecommendations(new MlUserRecommendationRequest(userId, vector.toMl(),
                        toMlFilters(filter), excluded.stream().sorted().toList(), pool, K));
                if (ranked == null) {
                    throw new MlServiceException("ML service returned no results");
                }
            } catch (MlServiceException ex) {
                log.warn("ML service unavailable, using the fallback ranker for For you: {}", ex.getMessage());
                ranked = fallbackRanker.rankForUser(pool, K);
            }
        }
        return enforce(ranked, filter, excluded);
    }

    /** Drops duplicates, excluded and unknown movies, and anything the filter rejects; keeps order. */
    private List<MlUserRecommendation> enforce(List<MlUserRecommendation> ranked, ContentFilter filter,
                                               Collection<Integer> excluded) {
        Map<Integer, MovieContent> content = contentFilter.contentOf(
                ranked.stream().map(MlUserRecommendation::movieId).distinct().toList());
        Set<Integer> seen = new HashSet<>();
        List<MlUserRecommendation> kept = new ArrayList<>();
        int violations = 0;
        for (MlUserRecommendation result : ranked) {
            if (kept.size() == K) {
                break;
            }
            if (!seen.add(result.movieId()) || excluded.contains(result.movieId())) {
                continue;
            }
            MovieContent movie = content.get(result.movieId());
            if (movie == null || !filter.isEligible(movie)) {
                violations++;
                continue;
            }
            kept.add(result);
        }
        if (violations > 0) {
            log.warn("Dropped {} For-you movie(s) that are unknown or violate the comfort filter ({})", violations,
                    filter.label());
        }
        return kept;
    }

    static MlFilters toMlFilters(ContentFilter filter) {
        return new MlFilters(null, null, null,
                filter.maxCertification() == null ? null : filter.maxCertification().label(),
                filter.contentLimitCodes(), filter.allowUnrated());
    }

    private static int startOf(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        String part = CursorCodec.decode(cursor, 1).getFirst();
        try {
            int start = Integer.parseInt(part);
            if (start < 0 || start > K) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
            return start;
        } catch (NumberFormatException ex) {
            throw new BadRequestException(CursorCodec.INVALID_CURSOR);
        }
    }
}
