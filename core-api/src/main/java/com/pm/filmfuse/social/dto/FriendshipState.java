package com.pm.filmfuse.social.dto;

/**
 * Where another user stands relative to the caller. {@code BLOCKED} means the caller blocked them;
 * the reverse (they blocked the caller) is never revealed - such users are simply not found.
 * Mirrored as a string union in client/types/api.ts.
 */
public enum FriendshipState {
    NONE,
    FRIENDS,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    BLOCKED
}
