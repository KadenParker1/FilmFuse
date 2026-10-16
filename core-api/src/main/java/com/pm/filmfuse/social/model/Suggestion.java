package com.pm.filmfuse.social.model;

import java.util.List;
import java.util.UUID;

/** A friend-of-a-friend candidate and (up to a few of) the viewer's friends they share. */
public record Suggestion(UUID candidateId, int mutualFriendCount, List<UUID> sampleMutualFriendIds) {
}
