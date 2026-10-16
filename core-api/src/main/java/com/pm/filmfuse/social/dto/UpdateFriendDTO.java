package com.pm.filmfuse.social.dto;

import jakarta.validation.constraints.NotNull;

/** {@code PATCH /friends/{userId}}: the explicit close-friend flag. Mirrored by hand in client/types/api.ts. */
public record UpdateFriendDTO(
        @NotNull(message = "closeFriend is required")
        Boolean closeFriend) {
}
