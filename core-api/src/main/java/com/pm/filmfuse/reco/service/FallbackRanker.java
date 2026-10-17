package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlGroupRecommendation;
import com.pm.filmfuse.reco.ml.MlUserRecommendation;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>FALLBACK ONLY - not the recommendation algorithm.</strong> Used when the ML service is
 * not configured ({@code ML_SERVICE_URL} blank) or fails. It keeps the eligible pool's order (most
 * popular first), gives every movie and member a neutral score and explains nothing beyond "Popular
 * pick". The real ranking lives in python-ml-service and belongs to the project owner; do not grow
 * this into one. Group sessions use {@link #rank}, personal "For you" {@link #rankForUser}.
 */
@Component
public class FallbackRanker {

    public static final String EXPLANATION = "Popular pick";
    public static final double NEUTRAL_SCORE = 5.0;

    /** The first {@code k} of {@code pool}, in pool order. */
    public List<MlGroupRecommendation> rank(List<Integer> pool, Collection<UUID> memberIds, int k) {
        Map<UUID, Double> neutral = new LinkedHashMap<>();
        memberIds.forEach(id -> neutral.put(id, NEUTRAL_SCORE));
        return pool.stream()
                .distinct()
                .limit(k)
                .map(movieId -> new MlGroupRecommendation(movieId, NEUTRAL_SCORE, neutral, EXPLANATION))
                .toList();
    }

    /** One user's "For you": the first {@code k} of {@code pool}, in pool order, neutral score. */
    public List<MlUserRecommendation> rankForUser(List<Integer> pool, int k) {
        return pool.stream()
                .distinct()
                .limit(k)
                .map(movieId -> new MlUserRecommendation(movieId, NEUTRAL_SCORE, EXPLANATION))
                .toList();
    }
}
