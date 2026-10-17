package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.movie.catalog.TmdbProperties;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.mapper.MovieMapper;
import com.pm.filmfuse.movie.repository.MovieRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Movie summaries read from the movie cache (callers only pass ids of cached movies; others are absent). */
@Component
public class MovieSummaries {

    private final MovieRepository movies;
    private final String imageBaseUrl;

    public MovieSummaries(MovieRepository movies, TmdbProperties tmdbProperties) {
        this.movies = movies;
        this.imageBaseUrl = tmdbProperties.imageBaseUrl();
    }

    public Map<Integer, MovieSummaryDTO> of(Collection<Integer> ids) {
        Map<Integer, MovieSummaryDTO> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        movies.findAllById(ids).forEach(movie -> result.put(movie.getId(),
                MovieMapper.toSummaryDTO(MovieMapper.toCatalogMovie(movie), imageBaseUrl)));
        return result;
    }
}
