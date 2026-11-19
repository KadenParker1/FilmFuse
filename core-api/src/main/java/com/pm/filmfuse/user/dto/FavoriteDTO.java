package com.pm.filmfuse.user.dto;

/**
 * An all-time favourite in {@code PUT /me/preferences/favorites} (rank 1 = top), or a least favourite
 * in {@code PUT /me/preferences/least-favorites} (rank 1 = most disliked). Mirrored by hand in client/types/api.ts. */
public record FavoriteDTO(Integer movieId, Integer rank) {
}
