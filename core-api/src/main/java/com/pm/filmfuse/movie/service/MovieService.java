package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.catalog.TmdbProperties;
import com.pm.filmfuse.movie.content.ContentLevelService;
import com.pm.filmfuse.movie.dto.MovieDetailDTO;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.mapper.MovieMapper;
import com.pm.filmfuse.movie.model.Movie;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.repository.MovieRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Movie browsing over the {@link MovieCatalogClient}, with {@code app.movies} as a cache: every
 * movie the catalog returns is upserted, and details are served from the cache while fresh.
 *
 * <p>Cursors are the next 1-based catalog page number as a string; the client treats them as opaque.
 */
@Service
public class MovieService {

    /** A cached row older than this is refetched on a details request. */
    static final Duration DETAILS_TTL = Duration.ofDays(7);
    static final String INVALID_CURSOR = "Invalid cursor.";
    static final String UNKNOWN_GENRE = "Unknown genre.";

    private final MovieCatalogClient catalogClient;
    private final MovieRepository movieRepository;
    private final GenreService genreService;
    private final ContentLevelService contentLevelService;
    private final MovieEnrichmentRepository enrichment;
    private final String imageBaseUrl;
    private final Clock clock;

    @Autowired
    public MovieService(MovieCatalogClient catalogClient, MovieRepository movieRepository,
                        GenreService genreService, ContentLevelService contentLevelService,
                        MovieEnrichmentRepository enrichment, TmdbProperties tmdbProperties) {
        this(catalogClient, movieRepository, genreService, contentLevelService, enrichment, tmdbProperties,
                Clock.systemUTC());
    }

    MovieService(MovieCatalogClient catalogClient, MovieRepository movieRepository, GenreService genreService,
                 ContentLevelService contentLevelService, MovieEnrichmentRepository enrichment,
                 TmdbProperties tmdbProperties, Clock clock) {
        this.catalogClient = catalogClient;
        this.movieRepository = movieRepository;
        this.genreService = genreService;
        this.contentLevelService = contentLevelService;
        this.enrichment = enrichment;
        this.imageBaseUrl = tmdbProperties.imageBaseUrl();
        this.clock = clock;
    }

    public PageDTO<MovieSummaryDTO> search(String query, String cursor) {
        return toPage(catalogClient.search(query.trim(), pageFrom(cursor)));
    }

    public PageDTO<MovieSummaryDTO> popular(Integer genreId, String cursor) {
        int page = pageFrom(cursor);
        if (genreId != null && !genreService.exists(genreId)) {
            throw new BadRequestException(UNKNOWN_GENRE);
        }
        return toPage(catalogClient.popular(genreId, page));
    }

    /**
     * From the cache when the row is younger than {@link #DETAILS_TTL} and has a runtime (rows
     * written from list results do not); otherwise from the catalog, refreshing the cache.
     */
    public MovieDetailDTO details(int id) {
        Instant now = clock.instant();
        CatalogMovie movie = movieRepository.findById(id)
                .filter(cached -> isFresh(cached, now))
                .map(MovieMapper::toCatalogMovie)
                .orElseGet(() -> {
                    CatalogMovie fetched = catalogClient.details(id);
                    cacheDetails(fetched, now);
                    return fetched;
                });
        return MovieMapper.toDetailDTO(movie, genreService.namesById(), imageBaseUrl);
    }

    /**
     * Makes sure every id has a row in {@code app.movies} (ratings, favourites and dismissals
     * reference it): ids already cached are left alone, the others are fetched from the catalog
     * and cached. An id the catalog does not know is a 404 ({@code NotFoundException}).
     */
    public void ensureCached(Collection<Integer> ids) {
        Set<Integer> cached = new HashSet<>();
        movieRepository.findAllById(ids).forEach(movie -> cached.add(movie.getId()));
        for (Integer id : new LinkedHashSet<>(ids)) {
            if (!cached.contains(id)) {
                CatalogMovie fetched = catalogClient.details(id);
                cacheDetails(fetched, clock.instant());
            }
        }
    }

    private PageDTO<MovieSummaryDTO> toPage(CatalogPage page) {
        cache(page.movies(), clock.instant());
        List<MovieSummaryDTO> items = page.movies().stream()
                .map(movie -> MovieMapper.toSummaryDTO(movie, imageBaseUrl))
                .toList();
        return new PageDTO<>(items, page.hasNext() ? String.valueOf(page.page() + 1) : null);
    }

    /** Upserts the movies, then re-derives content levels for any that carry content data. */
    private void cache(List<CatalogMovie> movies, Instant now) {
        movieRepository.upsertAll(movies, now);
        contentLevelService.refresh(movies, now);
    }

    /** A details fetch is the certification lookup, so the movie leaves the enrichment queue. */
    private void cacheDetails(CatalogMovie movie, Instant now) {
        cache(List.of(movie), now);
        enrichment.markChecked(List.of(movie.id()), now);
    }

    private static boolean isFresh(Movie cached, Instant now) {
        return cached.getRuntime() != null
                && cached.getCachedAt() != null
                && cached.getCachedAt().isAfter(now.minus(DETAILS_TTL));
    }

    /** No cursor is page 1. Anything but an integer in 1..500 is a 400. */
    static int pageFrom(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 1;
        }
        int page;
        try {
            page = Integer.parseInt(cursor.trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException(INVALID_CURSOR);
        }
        if (page < 1 || page > MovieCatalogClient.MAX_PAGE) {
            throw new BadRequestException(INVALID_CURSOR);
        }
        return page;
    }
}
