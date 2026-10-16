package com.pm.filmfuse.movie.repository;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import java.time.Instant;
import java.util.Collection;

/** Spring Data fragment: the write side of the movie cache. */
public interface MovieUpsertRepository {

    /**
     * Inserts or refreshes each movie, stamping {@code cached_at}. A null runtime (list results)
     * never erases a runtime an earlier details fetch stored.
     */
    void upsertAll(Collection<CatalogMovie> movies, Instant cachedAt);
}
