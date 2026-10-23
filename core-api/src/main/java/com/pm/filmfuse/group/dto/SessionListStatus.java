package com.pm.filmfuse.group.dto;

/**
 * {@code GET /sessions?status=}: OPEN = still taking votes ("Live now"); CLOSED = history (DECIDED,
 * EXPIRED or ended early). Omitted = both. Mirrored as a string union in client/types/api.ts.
 */
public enum SessionListStatus {
    OPEN,
    CLOSED
}
