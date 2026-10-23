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
 * {@code results}: by rank, with vetoed results listed last. While OPEN, results that no longer
 * pass the current comfort filter (e.g. after a late joiner with stricter settings) are left out for
 * everyone (and stay hidden once it is DECIDED / EXPIRED, under the filter it closed with);
 * {@code hiddenResultCount} says how many, without saying whose settings caused it.
 * {@code closesAt}: when voting closes on its own (created + 24 h). {@code endedEarly}: EXPIRED
 * because the creator ended it. {@code shareUrl}: the web link to this session.
 * Mirrored in client/types/api.ts.
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
        int hiddenResultCount,
        Instant closesAt,
        boolean endedEarly,
        String shareUrl,
        Instant createdAt) {
}
