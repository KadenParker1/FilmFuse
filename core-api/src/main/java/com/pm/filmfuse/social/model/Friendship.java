package com.pm.filmfuse.social.model;

import java.time.Instant;
import java.util.UUID;

/** A {@code friendships} row: a pending request from {@code requestedBy}, or an accepted friendship. */
public record Friendship(UserPair pair, FriendshipStatus status, UUID requestedBy, Instant createdAt,
                         Instant acceptedAt) {

    public boolean isAccepted() {
        return status == FriendshipStatus.ACCEPTED;
    }

    public boolean isPendingFrom(UUID user) {
        return status == FriendshipStatus.PENDING && requestedBy.equals(user);
    }
}
