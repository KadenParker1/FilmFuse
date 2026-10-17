package com.pm.filmfuse.rating.dto;

import java.util.Locale;

/**
 * Where a score came from. The API uses the constant names; the database ({@code ratings.source})
 * and the ML contract use {@link #code()} ({@code "onboarding_swipe"}). {@code ONBOARDING_SWIPE}
 * is written only by {@code POST /me/ratings/batch}. Mirrored as a string union in client/types/api.ts.
 */
public enum RatingSource {
    ONBOARDING_SWIPE,
    QUICK_RATE,
    POST_WATCH;

    /** "quick_rate": the database and ML-contract form. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The source with this code (case-insensitive); null for anything else. */
    public static RatingSource fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RatingSource source : values()) {
            if (source.code().equalsIgnoreCase(code.trim())) {
                return source;
            }
        }
        return null;
    }
}
