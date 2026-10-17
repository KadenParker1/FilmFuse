package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.reco.dto.EffectiveFilterDTO;
import java.util.List;
import java.util.UUID;

/**
 * A group as its members (ACTIVE or INVITED) see it. {@code role} / {@code memberStatus} are the
 * caller's. {@code memberCount} / {@code invitedCount} count every ACTIVE / INVITED member.
 * {@code members}: ACTIVE first (earliest joined first), then INVITED - leaving out anyone blocked
 * either way with the caller (so it can be shorter than the counts; SECURITY.md #15).
 * {@code effectiveFilter} = the ACTIVE members' comfort settings plus the cap, without attribution.
 * {@code recentSessions}: up to 5 of the group's sessions the caller took part in, newest first.
 * Mirrored in client/types/api.ts.
 */
public record GroupDetailDTO(
        UUID id,
        String name,
        String emoji,
        String color,
        UUID ownerId,
        GroupRole role,
        MemberStatus memberStatus,
        int memberCount,
        int invitedCount,
        List<GroupMemberDTO> members,
        ComfortCapDTO comfortCap,
        EffectiveFilterDTO effectiveFilter,
        List<SessionSummaryDTO> recentSessions) {
}
