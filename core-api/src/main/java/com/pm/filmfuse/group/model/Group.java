package com.pm.filmfuse.group.model;

import com.pm.filmfuse.reco.model.ContentFilter;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code app.groups}. {@code cap} is the owner's comfort cap ({@link ContentFilter#cap}). */
public record Group(
        UUID id,
        String name,
        String emoji,
        String color,
        UUID ownerId,
        ContentFilter cap,
        Instant createdAt) {
}
