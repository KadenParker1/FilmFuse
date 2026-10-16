package com.pm.filmfuse.group.dto;

/** INVITED until the invite is accepted (a decline removes the membership). Mirrored as a string union in client/types/api.ts. */
public enum MemberStatus {
    INVITED,
    ACTIVE
}
