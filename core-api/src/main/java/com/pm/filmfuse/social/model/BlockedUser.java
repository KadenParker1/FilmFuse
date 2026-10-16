package com.pm.filmfuse.social.model;

import java.time.Instant;

/** Someone the viewer blocked, and when. */
public record BlockedUser(SocialUser user, Instant blockedAt) {
}
