package com.pm.filmfuse.movie.dto;

import java.util.List;

/**
 * A movie in a list (search, popular). {@code id} is the TMDB id; {@code posterUrl} is absolute or
 * null. Mirrored by hand in client/types/api.ts - keep them in sync.
 */
public record MovieSummaryDTO(
        int id,
        String title,
        Integer releaseYear,
        String posterUrl,
        List<Integer> genreIds,
        Double voteAverage) {
}
