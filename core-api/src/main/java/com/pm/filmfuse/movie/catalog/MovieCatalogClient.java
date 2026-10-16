package com.pm.filmfuse.movie.catalog;

/**
 * The port to the external movie catalog. {@link TmdbMovieCatalogClient} in any environment with a
 * TMDB token; {@link FixtureMovieCatalogClient} otherwise (local dev, tests). Selected in
 * {@link MovieCatalogConfig}.
 *
 * <p>Failures: an unknown movie is a {@code NotFoundException}; an unreachable or failing catalog is
 * an {@code UpstreamUnavailableException} (502).
 */
public interface MovieCatalogClient {

    /** TMDB refuses pages beyond 500 on every list endpoint. */
    int MAX_PAGE = 500;

    CatalogPage search(String query, int page);

    /** Most popular first; {@code genreId} null for all genres. */
    CatalogPage popular(Integer genreId, int page);

    CatalogMovie details(int id);
}
