package com.pm.filmfuse.rating.model;

import java.util.UUID;

/** A rating's id, author and movie. */
public record RatingRef(UUID id, UUID authorId, int movieId) {
}
