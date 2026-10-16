package com.pm.filmfuse.social.model;

import java.util.UUID;

/** The public face of a profile, as the social module reads it (no email, no status). */
public record SocialUser(UUID id, String username, String displayName, String profileImageUrl) {
}
