package com.pm.filmfuse.group.model;

import java.time.Instant;

/** A group in someone's list, with their membership, the ACTIVE member count and the newest session. */
public record GroupListing(
        Group group,
        GroupMember membership,
        int activeCount,
        Instant lastSessionAt) {
}
