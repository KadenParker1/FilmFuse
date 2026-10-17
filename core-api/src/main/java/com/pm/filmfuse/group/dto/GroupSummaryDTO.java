package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A group in the caller's list. {@code role} / {@code memberStatus} are the caller's;
 * {@code memberCount} counts ACTIVE members; {@code memberPreview} is up to 5 of them, earliest
 * joined first, leaving out anyone blocked either way with the caller. {@code lastSessionAt} is the newest session of the group (null = none). Mirrored
 * in client/types/api.ts.
 */
public record GroupSummaryDTO(
        UUID id,
        String name,
        String emoji,
        String color,
        GroupRole role,
        MemberStatus memberStatus,
        int memberCount,
        List<UserSummaryDTO> memberPreview,
        Instant lastSessionAt) {
}
