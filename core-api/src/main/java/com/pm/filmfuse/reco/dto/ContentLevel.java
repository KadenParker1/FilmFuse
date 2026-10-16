package com.pm.filmfuse.reco.dto;

import java.util.Locale;

/**
 * How much of a {@link ContentDimension} a movie has, or the most a user accepts; ordered, so
 * {@link #ordinal()} is the comparison. As a limit, {@code STRONG} means "no limit". The database
 * and the ML contract use {@link #code()} (lowercase). Mirrored as a string union in
 * client/types/api.ts.
 */
public enum ContentLevel {
    NONE,
    MILD,
    MODERATE,
    STRONG;

    /** "mild": the database and ML-contract form. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isAtMost(ContentLevel max) {
        return ordinal() <= max.ordinal();
    }

    /** The level with this code (case-insensitive); null for anything else. */
    public static ContentLevel fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ContentLevel level : values()) {
            if (level.code().equalsIgnoreCase(code.trim())) {
                return level;
            }
        }
        return null;
    }

    public static ContentLevel min(ContentLevel a, ContentLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    public static ContentLevel max(ContentLevel a, ContentLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
