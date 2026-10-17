package com.pm.filmfuse.reco.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;

/** One "For you" pick. {@code certification} null = unknown. Mirrored by hand in client/types/api.ts. */
public record RecommendationDTO(
        MovieSummaryDTO movie,
        Certification certification,
        double score,
        String explanation) {
}
