package com.pm.filmfuse.rating.model;

import java.time.Instant;
import java.util.UUID;

/** One movie the user watched recently through a session and has not rated or dismissed. */
public record PendingRating(int movieId, UUID sessionId, String groupName, Instant pickedAt) {
}
