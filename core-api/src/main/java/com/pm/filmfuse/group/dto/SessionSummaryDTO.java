package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A session in a list ("Live now", history, a group's sessions). {@code memberCount} /
 * {@code votedCount} count every member; {@code memberPreview} is up to 5 members, other people
 * before the caller, leaving out anyone blocked either way with the caller. {@code votedByMe}:
 * whether the caller has voted on any result (false when they are not a member, e.g. a joinable
 * group session). {@code closesAt}: when voting closes on its own (created + 24 h).
 * {@code endedEarly}: EXPIRED because the creator ended it, not because time ran out.
 * Mirrored in client/types/api.ts.
 */
public record SessionSummaryDTO(
        UUID id,
        UUID groupId,
        String groupName,
        SessionStatus status,
        MovieSummaryDTO pickedMovie,
        int memberCount,
        int votedCount,
        boolean votedByMe,
        List<UserSummaryDTO> memberPreview,
        Instant closesAt,
        boolean endedEarly,
        Instant createdAt) {
}
