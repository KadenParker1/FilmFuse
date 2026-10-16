package com.pm.filmfuse.movie.catalog;

import com.pm.filmfuse.common.exception.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * An offline catalog of well-known movies (real TMDB ids) from {@code catalog/fixture-movies.json},
 * used whenever no TMDB token is configured: local development and every test. It behaves like TMDB
 * where it matters to callers - 20 results per page, list results without runtime, 404 for an
 * unknown id - so the code above it cannot tell the difference.
 */
public class FixtureMovieCatalogClient implements MovieCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(FixtureMovieCatalogClient.class);

    public static final String RESOURCE = "catalog/fixture-movies.json";
    public static final int PAGE_SIZE = 20;

    private final List<CatalogMovie> byPopularity;
    private final Map<Integer, CatalogMovie> byId;

    public FixtureMovieCatalogClient(List<CatalogMovie> movies) {
        this.byPopularity = movies.stream()
                .sorted(Comparator.comparing((CatalogMovie m) -> m.popularity() == null ? 0d : m.popularity())
                        .reversed()
                        .thenComparingInt(CatalogMovie::id))
                .toList();
        this.byId = movies.stream().collect(Collectors.toUnmodifiableMap(CatalogMovie::id, Function.identity()));
    }

    public static FixtureMovieCatalogClient fromClasspath() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<CatalogMovie> movies = JsonMapper.builder().build().readValue(in, new TypeReference<List<CatalogMovie>>() {
            });
            log.warn("TMDB_ACCESS_TOKEN is not set: serving the offline fixture catalog ({} movies from {}). "
                    + "Search, popular and details only know these movies.", movies.size(), RESOURCE);
            return new FixtureMovieCatalogClient(movies);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + RESOURCE, ex);
        }
    }

    @Override
    public CatalogPage search(String query, int page) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return paginate(byPopularity.stream()
                .filter(movie -> movie.title().toLowerCase(Locale.ROOT).contains(needle))
                .toList(), page);
    }

    @Override
    public CatalogPage popular(Integer genreId, int page) {
        return paginate(byPopularity.stream()
                .filter(movie -> genreId == null || movie.genreIds().contains(genreId))
                .toList(), page);
    }

    @Override
    public CatalogMovie details(int id) {
        CatalogMovie movie = byId.get(id);
        if (movie == null) {
            throw new NotFoundException(TmdbMovieCatalogClient.NOT_FOUND);
        }
        return movie;
    }

    /** Like TMDB: a page past the end is empty rather than an error, and there is always page 1. */
    private static CatalogPage paginate(List<CatalogMovie> matches, int page) {
        int totalPages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int from = Math.min((page - 1) * PAGE_SIZE, matches.size());
        int to = Math.min(from + PAGE_SIZE, matches.size());
        List<CatalogMovie> items = matches.subList(from, to).stream().map(CatalogMovie::withoutRuntime).toList();
        return new CatalogPage(items, page, totalPages);
    }
}
