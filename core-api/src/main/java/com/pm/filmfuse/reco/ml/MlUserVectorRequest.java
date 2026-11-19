package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.UUID;

/**
 * ML contract {@code UserVectorRequest} ({@code POST /internal/user-vectors}): every taste signal of
 * one user. {@code previousVector} null = no vector built yet. {@code leastFavorites} (by rank, 1 = most
 * disliked) is an additive, optional field (owner-approved 2026-11-08; the ML side defaults it to []).
 */
public record MlUserVectorRequest(
        UUID userId,
        MlUserVector previousVector,
        List<MlGenrePref> genrePrefs,
        List<MlMoodPref> moodPrefs,
        List<MlFavorite> favorites,
        List<MlFavorite> leastFavorites,
        List<MlRating> ratings,
        List<Integer> unseen,
        List<Integer> dismissals) {
}
