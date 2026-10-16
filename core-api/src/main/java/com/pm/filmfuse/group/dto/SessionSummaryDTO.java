package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import java.time.Instant;
import java.util.UUID;

/** A session in a history list. Mirrored in client/types/api.ts. */
public record SessionSummaryDTO(
        UUID id,
        UUID groupId,
        String groupName,
        SessionStatus status,
        MovieSummaryDTO pickedMovie,
        int memberCount,
        Instant createdAt) {
}
