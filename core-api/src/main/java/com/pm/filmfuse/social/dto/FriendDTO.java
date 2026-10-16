package com.pm.filmfuse.social.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's friends. {@code closeness} (0-1) is how close the caller is to them:
 * the implicit score, raised to at least 0.8 while {@code closeFriend} is set.
 * Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record FriendDTO(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        boolean closeFriend,
        double closeness,
        Instant friendsSince) {
}
