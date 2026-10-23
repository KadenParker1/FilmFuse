package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import java.util.ArrayList;
import java.util.List;

/**
 * The seeder's quality bar ({@code filmfuse.catalog.seed.quality.*}): poster, runtime, overview and a
 * minimum vote count, each switchable. Catalog plumbing - it decides what is worth caching, never how
 * movies rank.
 */
final class QualityFilter {

    private final CatalogSeedProperties.Quality quality;

    QualityFilter(CatalogSeedProperties.Quality quality) {
        this.quality = quality;
    }

    /** What a details fetch fails; empty = it passes. */
    List<String> failures(CatalogMovie movie) {
        List<String> failures = listFailures(movie);
        if (quality.requireRuntime() && (movie.runtime() == null || movie.runtime() <= 0)) {
            failures.add("runtime");
        }
        return failures;
    }

    /**
     * What a list result already fails (list results carry poster, overview and vote count, but no
     * runtime), so the details request can be saved.
     */
    List<String> listFailures(CatalogMovie movie) {
        List<String> failures = new ArrayList<>(3);
        if (quality.requirePoster() && (movie.posterPath() == null || movie.posterPath().isBlank())) {
            failures.add("poster");
        }
        if (quality.requireOverview() && (movie.overview() == null || movie.overview().isBlank())) {
            failures.add("overview");
        }
        if (quality.minVotes() > 0 && (movie.voteCount() == null || movie.voteCount() < quality.minVotes())) {
            failures.add("votes");
        }
        return failures;
    }
}
