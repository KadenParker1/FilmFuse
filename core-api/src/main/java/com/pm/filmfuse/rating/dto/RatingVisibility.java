package com.pm.filmfuse.rating.dto;

/**
 * Who may see a rating once the feed exists (Phase 2). Calibration swipes are always
 * {@code PRIVATE}. Stored by name. Mirrored as a string union in client/types/api.ts.
 */
public enum RatingVisibility {
    PRIVATE,
    FRIENDS,
    PUBLIC
}
