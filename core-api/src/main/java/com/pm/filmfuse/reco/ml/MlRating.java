package com.pm.filmfuse.reco.ml;

/**
 * ML contract {@code Rating}.
 *
 * @param source    lowercase contract value ("onboarding_swipe", "quick_rate", "post_watch")
 * @param createdAt ISO-8601 instant (a string so the wire form never depends on Jackson date settings)
 */
public record MlRating(int movieId, int score, String source, String createdAt) {
}
