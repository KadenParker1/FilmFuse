package com.pm.filmfuse.movie.mapper;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.dto.GenreDTO;
import com.pm.filmfuse.movie.dto.MovieDetailDTO;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.model.Genre;
import com.pm.filmfuse.movie.model.Movie;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Catalog / cache rows to DTOs. TMDB image paths become absolute URLs on {@code imageBaseUrl}
 * (which ends with {@code /}): posters at {@value #POSTER_SIZE}, backdrops at {@value #BACKDROP_SIZE}.
 */
public final class MovieMapper {

    public static final String POSTER_SIZE = "w500";
    public static final String BACKDROP_SIZE = "w1280";

    private MovieMapper() {
    }

    public static GenreDTO toDTO(Genre genre) {
        return new GenreDTO(genre.getId(), genre.getName());
    }

    public static MovieSummaryDTO toSummaryDTO(CatalogMovie movie, String imageBaseUrl) {
        return new MovieSummaryDTO(
                movie.id(),
                movie.title(),
                movie.releaseYear(),
                imageUrl(imageBaseUrl, POSTER_SIZE, movie.posterPath()),
                movie.genreIds(),
                movie.voteAverage());
    }

    /** Genre ids with no row in {@code genres} (TMDB added one) are left out rather than named "null". */
    public static MovieDetailDTO toDetailDTO(CatalogMovie movie, Map<Integer, String> genreNames, String imageBaseUrl) {
        List<GenreDTO> genres = movie.genreIds().stream()
                .filter(genreNames::containsKey)
                .map(id -> new GenreDTO(id, genreNames.get(id)))
                .toList();
        return new MovieDetailDTO(
                movie.id(),
                movie.title(),
                movie.releaseYear(),
                movie.runtime(),
                movie.overview(),
                imageUrl(imageBaseUrl, POSTER_SIZE, movie.posterPath()),
                imageUrl(imageBaseUrl, BACKDROP_SIZE, movie.backdropPath()),
                genres,
                movie.voteAverage(),
                movie.popularity());
    }

    public static CatalogMovie toCatalogMovie(Movie movie) {
        List<Integer> genreIds = movie.getGenreIds() == null ? List.of()
                : Arrays.stream(movie.getGenreIds()).filter(Objects::nonNull).toList();
        return new CatalogMovie(
                movie.getId(),
                movie.getTitle(),
                movie.getReleaseYear(),
                movie.getRuntime(),
                movie.getPosterPath(),
                movie.getBackdropPath(),
                movie.getOverview(),
                genreIds,
                movie.getPopularity(),
                movie.getVoteAverage(),
                movie.getCertification(),
                movie.getKeywords() == null ? List.of() : Arrays.asList(movie.getKeywords()));
    }

    /** {@code https://image.tmdb.org/t/p/} + {@code w500} + {@code /abc.jpg}; null when there is no image. */
    public static String imageUrl(String imageBaseUrl, String size, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return imageBaseUrl + size + (path.startsWith("/") ? path : "/" + path);
    }
}
