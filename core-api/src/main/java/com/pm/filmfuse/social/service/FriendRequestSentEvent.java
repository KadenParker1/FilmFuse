package com.pm.filmfuse.social.service;

import java.util.UUID;

/** Published inside the transaction that stored a new friend request; emailed after commit. */
public record FriendRequestSentEvent(UUID requesterId, UUID recipientId) {
}
