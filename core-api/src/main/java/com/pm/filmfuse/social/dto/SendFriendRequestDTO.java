package com.pm.filmfuse.social.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code POST /friends/requests}. Mirrored by hand in client/types/api.ts. */
public record SendFriendRequestDTO(
        @NotNull(message = "Choose who to send the request to")
        UUID targetUserId) {
}
