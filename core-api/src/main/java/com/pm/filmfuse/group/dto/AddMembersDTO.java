package com.pm.filmfuse.group.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /groups/{id}/members}: invite 1-19 of the caller's friends. People already in the
 * group (ACTIVE or INVITED) are ignored. Mirrored in client/types/api.ts.
 */
public record AddMembersDTO(
        @NotNull(message = "userIds is required")
        @Size(min = 1, max = 19, message = "Invite 1-19 people at a time")
        List<@NotNull(message = "User ids cannot be null") UUID> userIds) {
}
