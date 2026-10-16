package com.pm.filmfuse.movie.catalog;

import java.util.List;

/**
 * One page of catalog results. Pages are 1-based, as in TMDB.
 *
 * @param totalPages how many pages the catalog says exist (at least 1, even for no results)
 */
public record CatalogPage(List<CatalogMovie> movies, int page, int totalPages) {

    public CatalogPage {
        movies = movies == null ? List.of() : List.copyOf(movies);
    }

    public boolean hasNext() {
        return page < Math.min(totalPages, MovieCatalogClient.MAX_PAGE);
    }
}
