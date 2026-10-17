package com.pm.filmfuse.rating.service;

import java.util.UUID;

/** Published inside the transaction that stored a comment; the rating's author is emailed after commit. */
public record RatingCommentedEvent(UUID commentId, UUID ratingId, UUID ratingAuthorId, UUID commenterId,
                                   int movieId) {
}
