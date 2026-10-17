package com.pm.filmfuse.user.dto;

/** An all-time favourite in {@code PUT /me/preferences/favorites}; rank 1 = top. Mirrored by hand in client/types/api.ts. */
public record FavoriteDTO(Integer movieId, Integer rank) {
}
