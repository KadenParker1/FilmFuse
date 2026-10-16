package com.pm.filmfuse.movie.dto;

import java.util.List;

/** One movie's detail page. Image URLs are absolute or null. Mirrored by hand in client/types/api.ts. */
public record MovieDetailDTO(
        int id,
        String title,
        Integer releaseYear,
        Integer runtime,
        String overview,
        String posterUrl,
        String backdropUrl,
        List<GenreDTO> genres,
        Double voteAverage,
        Double popularity) {
}
