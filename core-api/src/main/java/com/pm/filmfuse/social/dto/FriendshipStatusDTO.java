package com.pm.filmfuse.social.dto;

import java.util.UUID;

/** The caller's relationship with {@code userId} after a write. Mirrored by hand in client/types/api.ts. */
public record FriendshipStatusDTO(UUID userId, FriendshipState friendship) {
}
