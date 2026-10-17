package com.pm.filmfuse.rating.model;

import java.util.UUID;

/** Who a comment belongs to: enough to decide who may delete it. */
public record CommentRef(UUID id, UUID ratingId, UUID authorId, UUID ratingAuthorId) {
}
