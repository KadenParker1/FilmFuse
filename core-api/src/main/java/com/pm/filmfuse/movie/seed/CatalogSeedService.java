package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogAuthenticationException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bulk-fills {@code app.movies} (and {@code movie_content_levels}) from the catalog so
 * recommendations have a deep candidate pool. Plumbing only: it decides nothing about ranking.
 *
 * <p><b>Sources</b> (both optional, combined, de-duplicated): the catalog's popular pages
 * 1..{@code popular-pages}, then the ids in {@code id-file}.
 *
 * <p><b>Per movie</b>: skipped when its details were fetched within {@code refresh-after}
 * ({@code certification_checked_at}, which only a details fetch stamps - list upserts never touch
 * it; 7 days, as {@code MovieService}'s details TTL). Otherwise {@code details(id)} (which carries
 * release_dates and keywords) is stored by {@link CatalogEnrichmentService#storeDetails} - the same
 * upsert, content-level derivation and checked stamp the enrichment job uses - in its own small
 * transaction. Each popular page's list results are upserted first, so a movie whose details fail
 * still gets a row that {@code CatalogEnrichmentJob} will finish later.
 *
 * <p><b>Errors</b>: unknown id - counted {@code notFound} (a cached row is stamped checked, as
 * enrichment does); 5xx / 429 / timeout - retried {@code max-retries} times with exponential
 * backoff, then counted {@code failed}; any other failure storing one movie - counted failed. One
 * movie never stops the run. A rejected token (401) is retried like an outage, but
 * {@code auth-failure-limit} consecutive 401s abort the run. A popular page that still fails after
 * its retries aborts too (the catalog is down; the cursor is kept).
 *
 * <p><b>Resume</b>: a resumable (full) run stores the next popular page in
 * {@code app.catalog_seed_state} after each page and deletes it when the pass completes, so a
 * stopped run continues at the page it stopped on; the skip rule makes everything else idempotent.
 * TMDB's popular order drifts day to day, so a resumed pass is approximate - the nightly top-up and
 * the next full pass catch what moved.
 *
 * <p>Every catalog request (retries included) goes through a {@link RequestPacer}
 * ({@code requests-per-second}). One run at a time per process.
 */
@Service
public class CatalogSeedService {

    static final String POPULAR_CURSOR = "popular";
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    /** Ids per skip-rule query when working through the id file. */
    private static final int ID_CHUNK = 200;

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedService.class);

    private final MovieCatalogClient catalog;
    private final CatalogEnrichmentService store;
    private final MovieEnrichmentRepository movies;
    private final CatalogSeedStateRepository state;
    private final CatalogSeedProperties properties;
    private final Clock clock;
    private final RequestPacer pacer;
    private final Sleeper sleeper;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public CatalogSeedService(MovieCatalogClient catalog, CatalogEnrichmentService store,
                              MovieEnrichmentRepository movies, CatalogSeedStateRepository state,
                              CatalogSeedProperties properties, Clock clock) {
        this(catalog, store, movies, state, properties, clock, RequestPacer.system(properties.requestsPerSecond()),
                Sleeper.SYSTEM);
    }

    CatalogSeedService(MovieCatalogClient catalog, CatalogEnrichmentService store, MovieEnrichmentRepository movies,
                       CatalogSeedStateRepository state, CatalogSeedProperties properties, Clock clock,
                       RequestPacer pacer, Sleeper sleeper) {
        this.catalog = catalog;
        this.store = store;
        this.movies = movies;
        this.state = state;
        this.properties = properties;
        this.clock = clock;
        this.pacer = pacer;
        this.sleeper = sleeper;
    }

    /**
     * What to seed.
     *
     * @param popularPages popular pages 1..n (0 = none; capped at {@link MovieCatalogClient#MAX_PAGE})
     * @param ids          explicit ids, after the popular pages
     * @param resumable    keep the popular-page cursor in {@code app.catalog_seed_state}
     */
    public record SeedPlan(int popularPages, List<Integer> ids, boolean resumable) {

        public SeedPlan {
            popularPages = Math.max(0, Math.min(popularPages, MovieCatalogClient.MAX_PAGE));
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    /** The full seed: {@code popular-pages} plus the {@code id-file}, resumable. */
    public SeedReport seedAll() {
        List<Integer> ids = properties.idFile() == null ? List.of() : SeedIdFile.read(Path.of(properties.idFile()));
        if (properties.idFile() != null) {
            log.info("Catalog seed: {} ids from {}", ids.size(), properties.idFile());
        }
        return seed(new SeedPlan(properties.popularPages(), ids, true));
    }

    /** The nightly top-up: popular pages 1..{@code top-up.pages}, from page 1 every time. */
    public SeedReport topUp() {
        return seed(new SeedPlan(properties.topUp().pages(), List.of(), false));
    }

    public SeedReport seed(SeedPlan plan) {
        Instant started = clock.instant();
        if (!running.compareAndSet(false, true)) {
            log.warn("Catalog seed not started: another run is in progress");
            return new Run(started).report(SeedReport.Outcome.ALREADY_RUNNING);
        }
        Run run = new Run(started);
        SeedReport.Outcome outcome = SeedReport.Outcome.COMPLETED;
        try {
            log.info("Catalog seed starting: popular pages {}, {} explicit ids, {} requests/s",
                    plan.popularPages(), plan.ids().size(), properties.requestsPerSecond());
            if (plan.popularPages() > 0) {
                seedPopular(plan, run);
            }
            for (int from = 0; from < plan.ids().size(); from += ID_CHUNK) {
                seedCandidates(plan.ids().subList(from, Math.min(from + ID_CHUNK, plan.ids().size())), run);
            }
        } catch (AbortRun ex) {
            log.error("Catalog seed aborted: {}", ex.getMessage());
            outcome = SeedReport.Outcome.ABORTED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Catalog seed interrupted");
            outcome = SeedReport.Outcome.INTERRUPTED;
        } finally {
            running.set(false);
        }
        SeedReport report = run.report(outcome);
        log.info(report.summary());
        return report;
    }

    private void seedPopular(SeedPlan plan, Run run) throws InterruptedException {
        int first = 1;
        if (plan.resumable()) {
            first = state.nextPage(POPULAR_CURSOR).filter(page -> page <= plan.popularPages()).orElse(1);
            if (first > 1) {
                log.info("Catalog seed resuming at popular page {}", first);
            }
        }
        for (int page = first; page <= plan.popularPages(); page++) {
            int current = page;
            CatalogPage result;
            try {
                result = call(run, () -> catalog.popular(null, current));
            } catch (NotFoundException ex) {
                break;
            } catch (UpstreamUnavailableException ex) {
                throw new AbortRun("popular page " + page + " still failing after " + properties.maxRetries()
                        + " retries; the next run resumes here");
            }
            run.pages++;
            store.storeListResults(result.movies());
            seedCandidates(result.movies().stream().map(CatalogMovie::id).toList(), run);
            if (plan.resumable()) {
                state.saveNextPage(POPULAR_CURSOR, page + 1);
            }
            if (result.movies().isEmpty() || !result.hasNext()) {
                break;
            }
        }
        if (plan.resumable()) {
            state.clear(POPULAR_CURSOR);
        }
    }

    private void seedCandidates(List<Integer> ids, Run run) throws InterruptedException {
        List<Integer> fresh = new ArrayList<>();
        for (Integer id : ids) {
            if (run.seen.add(id)) {
                fresh.add(id);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Set<Integer> seeded = movies.checkedSince(fresh, clock.instant().minus(properties.refreshAfter()));
        for (Integer id : fresh) {
            run.candidates++;
            if (seeded.contains(id)) {
                run.skipped++;
            } else {
                seedOne(id, run);
            }
            if (run.candidates % properties.progressEvery() == 0) {
                log.info("Catalog seed progress: {} candidates, {} fetched, {} already seeded, {} not found, "
                        + "{} failed, {} pages", run.candidates, run.fetched, run.skipped, run.notFound, run.failed,
                        run.pages);
            }
        }
    }

    private void seedOne(int id, Run run) throws InterruptedException {
        try {
            CatalogMovie details = call(run, () -> catalog.details(id));
            store.storeDetails(details);
            run.fetched++;
        } catch (NotFoundException ex) {
            store.markChecked(id);
            run.notFound++;
        } catch (AbortRun ex) {
            throw ex;
        } catch (UpstreamUnavailableException ex) {
            log.debug("Catalog seed: movie {} still failing after retries", id);
            run.failed++;
        } catch (RuntimeException ex) {
            log.warn("Catalog seed: could not store movie {}", id, ex);
            run.failed++;
        }
    }

    /**
     * One paced catalog request with retries. Not-found is passed straight through; outages back off
     * {@code initial-backoff * 2^attempt} (at most a minute) and give up after {@code max-retries}.
     */
    private <T> T call(Run run, Supplier<T> request) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            pacer.acquire();
            try {
                T result = request.get();
                run.consecutiveAuthFailures = 0;
                return result;
            } catch (CatalogAuthenticationException ex) {
                if (++run.consecutiveAuthFailures >= properties.authFailureLimit()) {
                    throw new AbortRun("the catalog rejected the access token " + run.consecutiveAuthFailures
                            + " times in a row (401) - check TMDB_ACCESS_TOKEN");
                }
                if (attempt >= properties.maxRetries()) {
                    throw ex;
                }
            } catch (NotFoundException ex) {
                run.consecutiveAuthFailures = 0;
                throw ex;
            } catch (UpstreamUnavailableException ex) {
                run.consecutiveAuthFailures = 0;
                if (attempt >= properties.maxRetries()) {
                    throw ex;
                }
            }
            sleeper.sleep(backoff(attempt));
        }
    }

    Duration backoff(int attempt) {
        Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(attempt, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** Stops the whole run; never caught per movie. */
    private static final class AbortRun extends RuntimeException {
        AbortRun(String message) {
            super(message, null, false, false);
        }
    }

    private final class Run {
        final Instant started;
        final Set<Integer> seen = new HashSet<>();
        int pages;
        int candidates;
        int fetched;
        int skipped;
        int notFound;
        int failed;
        int consecutiveAuthFailures;

        Run(Instant started) {
            this.started = started;
        }

        SeedReport report(SeedReport.Outcome outcome) {
            return new SeedReport(outcome, pages, candidates, fetched, skipped, notFound, failed,
                    Duration.between(started, clock.instant()));
        }
    }
}
