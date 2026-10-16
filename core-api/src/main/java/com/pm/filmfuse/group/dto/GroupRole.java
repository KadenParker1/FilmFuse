package com.pm.filmfuse.group.dto;

/** A member's role in a group. One OWNER per group. Mirrored as a string union in client/types/api.ts. */
public enum GroupRole {
    OWNER,
    MEMBER
}
