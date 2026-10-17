package com.pm.filmfuse.group.service;

import java.util.List;
import java.util.UUID;

/** Published inside the transaction that stored new group invites; each invitee is emailed after commit. */
public record GroupInvitesSentEvent(UUID groupId, String groupName, UUID inviterId, List<UUID> inviteeIds) {
}
