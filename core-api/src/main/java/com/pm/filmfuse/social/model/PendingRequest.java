package com.pm.filmfuse.social.model;

import java.time.Instant;

/** A pending request involving the viewer; {@code other} is the person on the other side. */
public record PendingRequest(SocialUser other, boolean outgoing, Instant createdAt) {
}
