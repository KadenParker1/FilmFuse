package com.pm.filmfuse.group.model;

import java.util.UUID;

/** A session member as snapshotted at creation, and whether they have voted yet. */
public record SessionMember(UUID userId, boolean hasVector, boolean voted) {
}
