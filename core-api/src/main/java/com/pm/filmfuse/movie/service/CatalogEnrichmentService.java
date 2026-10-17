package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.content.ContentLevelService;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.repository.MovieRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills in what list results leave out. TMDB's search / popular / discover results carry no
 * certification or keywords (only details does), so movies cached from lists are "unrated" and
 * never pass a certification cap with {@code allowUnrated = false}. This service
 * <ul>
 *   <li>{@link #enrich}: fetches details for the most popular movies never looked up
 *       ({@code certification_checked_at} null), upserts them (which never overwrites known values
 *       with null), recomputes their content levels and stamps them checked - also when TMDB has
 *       no US certification, or does not know the id, so nothing is fetched twice;</li>
 *   <li>{@link #seedIfSparse}: on a nearly empty cache (under {@code sparse-below}, 100 movies), pulls
 *       the catalog's popular pages 1-{@value #SEED_PAGES} in first.</li>
 * </ul>
 * Called synchronously by {@code ContentFilterService} when a pool comes up short, and every
 * 10 minutes by {@link CatalogEnrichmentJob}. Each movie is written in its own new transaction, so
 * callers may be inside a (read-only) transaction and a failure keeps what was done.
 */
@Service
public class CatalogEnrichmentService {

    public static final int SEED_PAGES = 3;

    private static final Logger log = LoggerFactory.getLogger(CatalogEnrichmentService.class);

    private final MovieCatalogClient catalog;
    private final MovieRepository movies;
    private final MovieEnrichmentRepository queue;
    private final ContentLevelService contentLevels;
    private final TransactionTemplate tx;
    private final int sparseBelow;
    private final Clock clock;

    public CatalogEnrichmentService(MovieCatalogClient catalog, MovieRepository movies, MovieEnrichmentRepository queue,
                                    ContentLevelService contentLevels, PlatformTransactionManager transactionManager,
                                    CatalogEnrichmentProperties properties, Clock clock) {
        this.catalog = catalog;
        this.movies = movies;
        this.queue = queue;
        this.contentLevels = contentLevels;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sparseBelow = properties.sparseBelow();
        this.clock = clock;
    }

    /**
     * Looks up to {@code limit} unchecked movies up in the catalog. Stops early (keeping what was
     * done) when the catalog is unavailable.
     *
     * @return how many movies were marked checked
     */
    public int enrich(int limit) {
        int checked = 0;
        for (Integer id : queue.unchecked(limit)) {
            try {
                CatalogMovie details = catalog.details(id);
                tx.executeWithoutResult(status -> {
                    Instant now = clock.instant();
                    movies.upsertAll(List.of(details), now);
                    contentLevels.refresh(List.of(details), now);
                    queue.markChecked(List.of(id), now);
                });
                checked++;
            } catch (NotFoundException ex) {
                // The catalog does not know this id: there is nothing to learn, so never ask again.
                tx.executeWithoutResult(status -> queue.markChecked(List.of(id), clock.instant()));
                checked++;
            } catch (UpstreamUnavailableException ex) {
                log.warn("Catalog enrichment stopped after {} movies: catalog unavailable", checked);
                break;
            }
        }
        if (checked > 0) {
            log.info("Catalog enrichment: {} movies checked", checked);
        }
        return checked;
    }

    public boolean hasUnchecked() {
        return queue.hasUnchecked();
    }

    /**
     * Under {@code sparse-below} cached movies: caches the catalog's popular pages
     * 1-{@value #SEED_PAGES} (as list results, to be enriched).
     *
     * @return whether anything was added
     */
    public boolean seedIfSparse() {
        if (sparseBelow == 0 || queue.count() >= sparseBelow) {
            return false;
        }
        boolean added = false;
        for (int page = 1; page <= SEED_PAGES; page++) {
            try {
                CatalogPage result = catalog.popular(null, page);
                if (!result.movies().isEmpty()) {
                    tx.executeWithoutResult(status -> movies.upsertAll(result.movies(), clock.instant()));
                    added = true;
                }
                if (!result.hasNext()) {
                    break;
                }
            } catch (UpstreamUnavailableException ex) {
                log.warn("Catalog seeding stopped at page {}: catalog unavailable", page);
                break;
            }
        }
        return added;
    }
}
