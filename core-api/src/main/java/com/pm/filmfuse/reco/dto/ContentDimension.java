package com.pm.filmfuse.reco.dto;

import java.util.Locale;

/**
 * A kind of content a user can limit. The API uses the constant names; the database and the ML
 * contract use {@link #code()} (lowercase). Mirrored as a string union in client/types/api.ts.
 */
public enum ContentDimension {
    VIOLENCE,
    SEXUAL,
    LANGUAGE,
    DRUGS,
    FRIGHTENING;

    /**
     * The strictest limit a user or group can set for this dimension. Violence and frightening
     * start at {@code MILD}: the content tagger never rates a film "none" for them (G and PG films
     * have cartoon peril), so a NONE limit would match almost nothing. Lower limits are raised to
     * this by {@code ContentFilter}.
     */
    public ContentLevel lowestLimit() {
        return switch (this) {
            case VIOLENCE, FRIGHTENING -> ContentLevel.MILD;
            case SEXUAL, LANGUAGE, DRUGS -> ContentLevel.NONE;
        };
    }

    /** "violence": the database and ML-contract form. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The dimension with this code (case-insensitive); null for anything else. */
    public static ContentDimension fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ContentDimension dimension : values()) {
            if (dimension.code().equalsIgnoreCase(code.trim())) {
                return dimension;
            }
        }
        return null;
    }
}
