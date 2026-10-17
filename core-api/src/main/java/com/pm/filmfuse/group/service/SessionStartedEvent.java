package com.pm.filmfuse.group.service;

import java.util.List;
import java.util.UUID;

/**
 * Published inside the transaction that created a session; every member except the creator is
 * emailed after commit. {@code groupName} is null for an ad-hoc session.
 */
public record SessionStartedEvent(UUID sessionId, UUID creatorId, String groupName, List<UUID> memberIds) {
}
