package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.reco.dto.EffectiveFilterDTO;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A recommendation session, visible to its members only. {@code groupId} / {@code groupName} are
 * null for an ad-hoc session (or once the group is deleted). {@code createdBy} is null only if the
 * creator has since deleted their account, or is blocked either way with the caller.
 * {@code memberCount} / {@code votedCount} count every member; {@code members} (and each result's
 * {@code memberScores} keys) leave out anyone blocked either way with the caller (SECURITY.md #15).
 * {@code results}: by rank, with vetoed results listed last. Mirrored in client/types/api.ts.
 */
public record SessionDTO(
        UUID id,
        UUID groupId,
        String groupName,
        UserSummaryDTO createdBy,
        SessionStatus status,
        Strategy strategy,
        SessionFiltersDTO filters,
        EffectiveFilterDTO effectiveFilter,
        int memberCount,
        int votedCount,
        List<SessionMemberDTO> members,
        List<SessionResultDTO> results,
        MovieSummaryDTO pickedMovie,
        Instant createdAt) {
}
