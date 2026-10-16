package com.pm.filmfuse.group.dto;

/** OPEN sessions older than 24 hours are reported as EXPIRED (computed, not stored). Mirrored as a string union in client/types/api.ts. */
public enum SessionStatus {
    OPEN,
    DECIDED,
    EXPIRED
}
