package com.pm.filmfuse.social.dto;

import java.util.UUID;

/**
 * {@code GET /users/{id}}. {@code tasteOverlap} is always null until interest vectors exist
 * (Phase 1b). {@code closeFriend} is true only when the caller and this user are FRIENDS and the
 * caller has flagged them as a close friend (it is directional). Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record PublicProfileDTO(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        String bio,
        FriendshipState friendship,
        boolean closeFriend,
        int mutualFriendCount,
        int friendCount,
        Double tasteOverlap) {
}
