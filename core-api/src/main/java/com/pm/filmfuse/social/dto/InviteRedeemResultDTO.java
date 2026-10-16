package com.pm.filmfuse.social.dto;

/** After redeeming: who invited the caller ({@code inviter.friendship} is FRIENDS). Mirrored by hand in client/types/api.ts. */
public record InviteRedeemResultDTO(UserSummaryDTO inviter) {
}
