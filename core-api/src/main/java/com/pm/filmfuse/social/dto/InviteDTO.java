package com.pm.filmfuse.social.dto;

import java.time.Instant;

/**
 * A freshly created invite link. The token is shown only here (only its hash is stored), and the
 * link works for anyone until {@code expiresAt}. Mirrored by hand in client/types/api.ts.
 */
public record InviteDTO(String token, String url, Instant expiresAt) {
}
