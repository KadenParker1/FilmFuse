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
