package com.pm.filmfuse.reco.dto;

/**
 * State of the caller's interest vector: {@code NONE} = no vector built and no taste signal waiting;
 * {@code FRESH} = built and up to date; {@code REBUILDING} = a taste signal arrived since the last
 * build (the row is dirty) and a rebuild is pending. Mirrored as a string union in client/types/api.ts.
 */
public enum VectorStatus {
    NONE,
    FRESH,
    REBUILDING
}
