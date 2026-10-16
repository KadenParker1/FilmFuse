package com.pm.filmfuse.group.dto;

import jakarta.validation.constraints.NotNull;

/** {@code PUT /sessions/{id}/votes/{movieId}}; replaces any earlier vote. Mirrored in client/types/api.ts. */
public record CastVoteDTO(
        @NotNull(message = "vote is required")
        Vote vote) {
}
