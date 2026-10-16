package com.pm.filmfuse.group.model;

import com.pm.filmfuse.group.dto.GroupRole;
import com.pm.filmfuse.group.dto.MemberStatus;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code app.group_members}. {@code joinedAt} is null while INVITED. */
public record GroupMember(
        UUID groupId,
        UUID userId,
        GroupRole role,
        MemberStatus status,
        Instant invitedAt,
        Instant joinedAt) {

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public boolean isOwner() {
        return role == GroupRole.OWNER;
    }
}
