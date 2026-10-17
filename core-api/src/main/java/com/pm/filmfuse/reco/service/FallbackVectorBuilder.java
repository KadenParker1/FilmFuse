package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.reco.ml.MlGenrePref;
import com.pm.filmfuse.reco.ml.MlMoodPref;
import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.ml.MlUserVectorRequest;
import com.pm.filmfuse.reco.ml.MlUserVectorResponse;
import com.pm.filmfuse.reco.model.TasteDimensions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * <strong>FALLBACK ONLY - not the interest-vector algorithm.</strong> Used when the ML service is
 * not configured, or fails for a user who has no vector yet. It copies the user's <em>stated</em>
 * genre then mood scores into the fixed {@link TasteDimensions} order (unset = neutral 5), ignores
 * ratings, favourites, unseen and dismissals, and has no latent part. Labelled
 * {@value #MODEL_VERSION} so it is never mistaken for a real model. The real vector construction
 * lives in python-ml-service and belongs to the project owner; do not grow this into one.
 */
@Component
public class FallbackVectorBuilder {

    public static final String MODEL_VERSION = "fallback-0";

    public MlUserVectorResponse build(MlUserVectorRequest request) {
        Map<Integer, Integer> genres = new HashMap<>();
        for (MlGenrePref pref : request.genrePrefs()) {
            genres.put(pref.genreId(), pref.score());
        }
        Map<String, Integer> moods = new HashMap<>();
        for (MlMoodPref pref : request.moodPrefs()) {
            moods.put(pref.moodTag(), pref.score());
        }
        List<Double> interpretable = new ArrayList<>(TasteDimensions.INTERPRETABLE_DIM);
        TasteDimensions.GENRE_IDS.forEach(id -> interpretable.add(score(genres.get(id))));
        TasteDimensions.MOOD_TAGS.forEach(tag -> interpretable.add(score(moods.get(tag))));
        return new MlUserVectorResponse(new MlUserVector(interpretable, null), TasteDimensions.INTERPRETABLE_DIM,
                MODEL_VERSION);
    }

    private static double score(Integer stated) {
        return stated == null ? TasteDimensions.NEUTRAL_SCORE : stated.doubleValue();
    }
}
