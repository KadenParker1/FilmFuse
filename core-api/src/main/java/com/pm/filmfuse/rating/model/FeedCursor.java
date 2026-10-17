package com.pm.filmfuse.rating.model;

import java.time.Instant;
import java.util.UUID;

/** The feed keyset: the (updated_at, id) of the last item on a page; the next page is strictly after it. */
public record FeedCursor(Instant updatedAt, UUID id) {
}
