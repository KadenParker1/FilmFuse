package com.pm.filmfuse.social.service;

import java.util.UUID;

/** Published inside the transaction that accepted {@code requesterId}'s request; emailed after commit. */
public record FriendRequestAcceptedEvent(UUID accepterId, UUID requesterId) {
}
