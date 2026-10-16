package com.pm.filmfuse.reco.dto;

import java.util.Locale;

/**
 * US (MPAA) certification, ordered least to most mature - {@link #ordinal()} order is the comparison
 * used by the comfort filter. The API uses the constant names ({@code PG_13}); the database, TMDB
 * and the ML contract use {@link #label()} ({@code "PG-13"}). Mirrored as a string union in
 * client/types/api.ts.
 */
public enum Certification {
    G("G"),
    PG("PG"),
    PG_13("PG-13"),
    R("R"),
    NC_17("NC-17");

    private final String label;

    Certification(String label) {
        this.label = label;
    }

    /** "PG-13": the display, database and ML-contract form. */
    public String label() {
        return label;
    }

    public boolean isAtMost(Certification max) {
        return ordinal() <= max.ordinal();
    }

    /** The certification with this label ("PG-13", case-insensitive, trimmed); null for anything else (e.g. "NR"). */
    public static Certification fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String wanted = label.trim().toUpperCase(Locale.ROOT);
        for (Certification certification : values()) {
            if (certification.label.equals(wanted)) {
                return certification;
            }
        }
        return null;
    }

    /** The more restrictive of two caps, where null means "no limit". */
    public static Certification stricter(Certification a, Certification b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
