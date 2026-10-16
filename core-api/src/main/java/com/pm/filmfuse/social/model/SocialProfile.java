package com.pm.filmfuse.social.model;

import com.pm.filmfuse.user.model.AccountStatus;

/** A profile as the public-profile page needs it. */
public record SocialProfile(SocialUser user, String bio, AccountStatus status) {
}
