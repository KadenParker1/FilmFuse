package com.pm.filmfuse.rating.mapper;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.rating.dto.RatingDTO;
import com.pm.filmfuse.rating.model.Rating;

public final class RatingMapper {

    private RatingMapper() {
    }

    public static RatingDTO toDTO(Rating rating, MovieSummaryDTO movie) {
        return new RatingDTO(rating.id(), movie, rating.score(), rating.body(), rating.containsSpoilers(),
                rating.visibility(), rating.source(), rating.watchedOn(), rating.createdAt(), rating.updatedAt());
    }
}
