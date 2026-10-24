package com.pm.filmfuse.movie.catalog;

import java.util.List;

/**
 * One page of TMDB's movie change list ({@code /movie/changes}): ids whose data changed in a date
 * window, adult titles left out. Pages are 1-based, 100 ids each on TMDB.
 *
 * @param totalPages how many pages the catalog says exist (at least 1, even for no changes)
 */
public record ChangedIdsPage(List<Integer> ids, int page, int totalPages) {

    public ChangedIdsPage {
        ids = ids == null ? List.of() : List.copyOf(ids);
        totalPages = Math.max(1, totalPages);
    }

    public boolean hasNext() {
        return page < Math.min(totalPages, MovieCatalogClient.MAX_PAGE);
    }
}
