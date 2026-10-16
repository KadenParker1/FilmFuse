package com.pm.filmfuse.movie.catalog;

import java.util.List;

/**
 * A movie as a {@link MovieCatalogClient} returns it - independent of TMDB's wire format. Image
 * fields are TMDB file paths ({@code /abc.jpg}), not URLs. {@code runtime} is null from list
 * endpoints (search / popular); only {@link MovieCatalogClient#details} fills it in.
 */
public record CatalogMovie(
        int id,
        String title,
        Integer releaseYear,
        Integer runtime,
        String posterPath,
        String backdropPath,
        String overview,
        List<Integer> genreIds,
        Double popularity,
        Double voteAverage) {

    public CatalogMovie {
        genreIds = genreIds == null ? List.of() : List.copyOf(genreIds);
    }

    /** The same movie as a list endpoint would return it (no runtime). */
    public CatalogMovie withoutRuntime() {
        return new CatalogMovie(id, title, releaseYear, null, posterPath, backdropPath, overview, genreIds,
                popularity, voteAverage);
    }
}
