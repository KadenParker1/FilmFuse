package com.pm.filmfuse.user.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;

/** A stored favourite, by rank. Mirrored by hand in client/types/api.ts. */
public record FavoriteMovieDTO(int rank, MovieSummaryDTO movie) {
}
