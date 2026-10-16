package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.social.dto.UserSummaryDTO;

/**
 * A session member. {@code hasVector}: whether they had an interest vector when the session was
 * created (false = recommendations used a popularity prior for them). {@code voted}: whether they
 * have voted on any result. Mirrored in client/types/api.ts.
 */
public record SessionMemberDTO(
        UserSummaryDTO user,
        boolean hasVector,
        boolean voted) {
}
