package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlFilters;
import com.pm.filmfuse.reco.ml.MlGroupMember;
import com.pm.filmfuse.reco.ml.MlGroupRecommendation;
import com.pm.filmfuse.reco.ml.MlGroupRecommendationRequest;
import com.pm.filmfuse.reco.ml.MlServiceClient;
import com.pm.filmfuse.reco.ml.MlServiceException;
import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.model.GroupRecommendationInput;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.model.RankedMovie;
import com.pm.filmfuse.reco.model.UserVectorLookup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Plumbing around the group ranker - no ranking logic of its own:
 * <ol>
 *   <li>builds the eligible candidate pool (comfort filter + session filters, minus exclusions);</li>
 *   <li>asks the ML service to rank within it, or the {@link FallbackRanker} when the service is not
 *       configured or fails;</li>
 *   <li><strong>always</strong> re-checks the ranked movies against the same filters and drops any
 *       violation (logged at WARN): a buggy ranker can never leak an over-limit movie.</li>
 * </ol>
 * Runs outside any transaction, so a slow ML call never holds a database connection's transaction.
 */
@Service
public class GroupRecommender {

    private static final Logger log = LoggerFactory.getLogger(GroupRecommender.class);

    private final ContentFilterService contentFilter;
    private final MlServiceClient mlClient;
    private final FallbackRanker fallbackRanker;
    private final UserVectorLookup vectorLookup;

    public GroupRecommender(ContentFilterService contentFilter, MlServiceClient mlClient,
                            FallbackRanker fallbackRanker, UserVectorLookup vectorLookup) {
        this.contentFilter = contentFilter;
        this.mlClient = mlClient;
        this.fallbackRanker = fallbackRanker;
        this.vectorLookup = vectorLookup;
    }

    /** At most {@code input.k()} eligible movies, best first. Empty when nothing is eligible. */
    public List<RankedMovie> recommend(GroupRecommendationInput input) {
        List<Integer> pool = contentFilter.eligiblePool(input.filter(), input.criteria(), input.excluded(),
                ContentFilterService.POOL_SIZE);
        if (pool.isEmpty()) {
            return List.of();
        }
        return enforce(rank(input, pool), input);
    }

    private List<MlGroupRecommendation> rank(GroupRecommendationInput input, List<Integer> pool) {
        if (!mlClient.isConfigured()) {
            return fallbackRanker.rank(pool, input.memberIds(), input.k());
        }
        try {
            return mlClient.groupRecommendations(toRequest(input, pool));
        } catch (MlServiceException ex) {
            log.warn("ML service unavailable, using the fallback ranker: {}", ex.getMessage(), ex.getCause());
            return fallbackRanker.rank(pool, input.memberIds(), input.k());
        }
    }

    MlGroupRecommendationRequest toRequest(GroupRecommendationInput input, List<Integer> pool) {
        Map<UUID, MlUserVector> vectors = vectorLookup.vectorsOf(input.memberIds());
        List<MlGroupMember> members = input.memberIds().stream()
                .map(id -> new MlGroupMember(id, vectors.get(id), 1.0))
                .toList();
        List<List<Double>> closeness = input.closeness() == null ? List.of()
                : Arrays.stream(input.closeness()).map(row -> Arrays.stream(row).boxed().toList()).toList();
        var filter = input.filter();
        var criteria = input.criteria();
        MlFilters filters = new MlFilters(criteria.maxRuntime(), criteria.minYear(),
                criteria.genreIds().isEmpty() ? null : criteria.genreIds(),
                filter.maxCertification() == null ? null : filter.maxCertification().label(),
                filter.contentLimitCodes(), filter.allowUnrated());
        return new MlGroupRecommendationRequest(members, closeness, filters,
                input.excluded().stream().sorted().toList(), pool, input.strategy(), input.k());
    }

    /** Drops duplicates, excluded and unknown movies, and anything the filters reject; keeps order. */
    private List<RankedMovie> enforce(List<MlGroupRecommendation> ranked, GroupRecommendationInput input) {
        Map<Integer, MovieContent> content = contentFilter.contentOf(
                ranked.stream().map(MlGroupRecommendation::movieId).distinct().toList());
        Set<Integer> seen = new HashSet<>();
        List<RankedMovie> kept = new ArrayList<>();
        int violations = 0;
        for (MlGroupRecommendation result : ranked) {
            if (kept.size() == input.k()) {
                break;
            }
            if (!seen.add(result.movieId()) || input.excluded().contains(result.movieId())) {
                continue;
            }
            MovieContent movie = content.get(result.movieId());
            if (movie == null || !contentFilter.isEligible(movie, input.filter()) || !input.criteria().matches(movie)) {
                violations++;
                continue;
            }
            kept.add(new RankedMovie(result.movieId(), result.groupScore(), result.memberScores(),
                    result.explanation(), movie.certification()));
        }
        if (violations > 0) {
            log.warn("Dropped {} ranked movie(s) that are unknown or violate the session's filters ({})",
                    violations, input.filter().label());
        }
        return kept;
    }
}
