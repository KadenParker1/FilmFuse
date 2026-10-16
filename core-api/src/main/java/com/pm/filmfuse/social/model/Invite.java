package com.pm.filmfuse.social.model;

import java.time.Instant;
import java.util.UUID;

/** An {@code app.invites} row. The token itself is never stored, only its SHA-256. */
public record Invite(UUID id, UUID inviterId, Instant createdAt, Instant expiresAt) {
}
