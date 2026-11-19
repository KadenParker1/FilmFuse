package com.pm.filmfuse.user.dto;

import java.util.List;

/**
 * {@code GET /me/onboarding}: everything saved so far, so the flow can resume. {@code step} 0-8
 * only ever increases; {@code calibrationRatedCount} counts calibration swipes that gave a score.
 * {@code leastFavorites} (optional, 0-5, by rank) plays no part in completion.
 * Mirrored by hand in client/types/api.ts.
 */
public record OnboardingStateDTO(
        int step,
        boolean completed,
        List<GenrePrefDTO> genrePrefs,
        List<MoodPrefDTO> moodPrefs,
        List<FavoriteMovieDTO> favorites,
        List<FavoriteMovieDTO> leastFavorites,
        long calibrationRatedCount,
        ComfortSettingsDTO comfort) {
}
