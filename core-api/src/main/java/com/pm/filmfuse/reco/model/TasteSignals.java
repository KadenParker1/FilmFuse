package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.ml.MlFavorite;
import com.pm.filmfuse.reco.ml.MlGenrePref;
import com.pm.filmfuse.reco.ml.MlMoodPref;
import com.pm.filmfuse.reco.ml.MlRating;
import java.util.List;

/** Every taste signal of one user, already in ML-contract form (see TasteSignalRepository for order). */
public record TasteSignals(
        List<MlGenrePref> genrePrefs,
        List<MlMoodPref> moodPrefs,
        List<MlFavorite> favorites,
        List<MlRating> ratings,
        List<Integer> unseen,
        List<Integer> dismissals) {
}
