package com.pm.filmfuse.group.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /sessions}: exactly one of {@code groupId} (the group's ACTIVE members; the caller
 * must be one) or {@code memberIds} (1-19 of the caller's friends, an ad-hoc session; the caller is
 * added automatically). {@code strategy} null = MEAN. Mirrored in client/types/api.ts.
 */
public record CreateSessionDTO(
        UUID groupId,

        @Size(min = 1, max = 19, message = "Pick 1-19 friends")
        List<@NotNull(message = "Member ids cannot be null") UUID> memberIds,

        @Valid
        SessionFiltersDTO filters,

        Strategy strategy) {
}
