package com.pm.filmfuse.social.dto;

import java.time.Instant;

/** A pending request, from the caller's point of view. Mirrored by hand in client/types/api.ts. */
public record FriendRequestDTO(UserSummaryDTO user, RequestDirection direction, Instant createdAt) {
}
