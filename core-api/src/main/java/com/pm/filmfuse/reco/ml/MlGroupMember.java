package com.pm.filmfuse.reco.ml;

import java.util.UUID;

/** ML contract {@code GroupMember}. {@code vector} null = the member has no vector yet. */
public record MlGroupMember(UUID userId, MlUserVector vector, double weight) {
}
