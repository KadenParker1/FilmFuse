package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;

/** One member of a group. {@code joinedAt} is null while INVITED. Mirrored in client/types/api.ts. */
public record GroupMemberDTO(
        UserSummaryDTO user,
        GroupRole role,
        MemberStatus status,
        Instant joinedAt) {
}
