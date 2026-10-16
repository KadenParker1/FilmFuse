package com.pm.filmfuse.group.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /sessions/{id}/pick}. Mirrored in client/types/api.ts. */
public record PickMovieDTO(
        @NotNull(message = "movieId is required")
        Integer movieId) {
}
