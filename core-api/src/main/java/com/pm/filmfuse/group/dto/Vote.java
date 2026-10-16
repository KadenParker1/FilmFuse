package com.pm.filmfuse.group.dto;

/** A member's vote on one session result. A single VETO takes the movie out of the running. Mirrored as a string union in client/types/api.ts. */
public enum Vote {
    UP,
    DOWN,
    VETO
}
