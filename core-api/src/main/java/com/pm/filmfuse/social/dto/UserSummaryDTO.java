package com.pm.filmfuse.social.dto;

import java.util.UUID;

/**
 * Another user as shown in lists (search, requests, blocks, suggestions). {@code username} is null
 * only for a user who has not picked one yet (reachable by id, e.g. a friend made through an invite).
 * Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record UserSummaryDTO(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        FriendshipState friendship) {
}
