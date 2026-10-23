package com.pm.filmfuse.group.service;

import java.util.List;
import java.util.UUID;

/**
 * Published inside the transaction that added people to an OPEN session; each of
 * {@code addedIds} is emailed after commit, like the original invitees. {@code groupName} is null
 * for an ad-hoc session.
 */
public record SessionMembersAddedEvent(UUID sessionId, UUID adderId, String groupName, List<UUID> addedIds) {
}
