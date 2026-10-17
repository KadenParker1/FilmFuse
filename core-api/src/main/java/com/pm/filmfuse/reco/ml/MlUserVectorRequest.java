package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.UUID;

/**
 * ML contract {@code UserVectorRequest} ({@code POST /internal/user-vectors}): every taste signal of
 * one user. {@code previousVector} null = no vector built yet.
 */
public record MlUserVectorRequest(
        UUID userId,
        MlUserVector previousVector,
        List<MlGenrePref> genrePrefs,
        List<MlMoodPref> moodPrefs,
        List<MlFavorite> favorites,
        List<MlRating> ratings,
        List<Integer> unseen,
        List<Integer> dismissals) {
}
