package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import java.time.Instant;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code movie_content_levels} in step with the movie cache: whenever a movie is cached with
 * content data (a details fetch: certification and/or keywords), its derived levels are recomputed
 * by the {@link ContentLevelProvider}. List results carry no content data and change nothing.
 */
@Service
public class ContentLevelService {

    private final ContentLevelProvider provider;
    private final MovieContentLevelRepository repository;

    public ContentLevelService(ContentLevelProvider provider, MovieContentLevelRepository repository) {
        this.provider = provider;
        this.repository = repository;
    }

    /** Call after the movies themselves are upserted (the levels reference them). */
    @Transactional
    public void refresh(Collection<CatalogMovie> movies, Instant now) {
        for (CatalogMovie movie : movies) {
            if (movie.hasContentData()) {
                repository.replace(movie.id(), provider.source(), provider.estimate(movie), now);
            }
        }
    }
}
