package com.pm.filmfuse.group.dto;

/**
 * OPEN sessions older than 24 hours are reported as EXPIRED (computed, not stored); a session the
 * creator ends early is stored as EXPIRED (see {@code endedEarly}). Mirrored as a string union in
 * client/types/api.ts.
 */
public enum SessionStatus {
    OPEN,
    DECIDED,
    EXPIRED
}
