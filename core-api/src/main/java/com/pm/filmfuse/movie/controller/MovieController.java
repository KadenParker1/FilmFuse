package com.pm.filmfuse.movie.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.movie.dto.MovieDetailDTO;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Discover: search, popular (optionally by genre) and movie details, backed by TMDB. */
@RestController
@RequestMapping("/api/v1/movies")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    /** Title search. 400 when {@code q} is missing, blank or over 100 characters. */
    @GetMapping("/search")
    public PageDTO<MovieSummaryDTO> search(
            @RequestParam @NotBlank(message = "Enter something to search for")
            @Size(max = 100, message = "Search text cannot exceed 100 characters") String q,
            @RequestParam(required = false) String cursor) {
        return movieService.search(q, cursor);
    }

    /** Most popular first; {@code genre} is an optional TMDB genre id (400 if unknown). */
    @GetMapping("/popular")
    public PageDTO<MovieSummaryDTO> popular(
            @RequestParam(required = false) @Positive(message = "Must be a TMDB genre id") Integer genre,
            @RequestParam(required = false) String cursor) {
        return movieService.popular(genre, cursor);
    }

    /** 404 when TMDB does not know the id. */
    @GetMapping("/{id}")
    public MovieDetailDTO details(@PathVariable @Positive(message = "Must be a TMDB movie id") int id) {
        return movieService.details(id);
    }
}
