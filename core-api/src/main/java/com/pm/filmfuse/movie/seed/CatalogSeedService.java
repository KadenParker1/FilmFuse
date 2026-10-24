package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties.Source;
import com.pm.filmfuse.movie.seed.CatalogSeedStateRepository.Cursor;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>Sources</b> (each optional, combined, de-duplicated, in this order; {@code sources} picks them
 * for a full seed): the catalog's popular pages 1..{@code popular-pages}; the ids in {@code id-file};
 * TMDB's daily id export ({@link TmdbIdExport}: the {@code export.max-movies} most popular ids);
 * discover-by-year ({@code /discover/movie} per release year, most votes first).
 *
 * <p><b>Per movie</b>: skipped when its details were fetched within {@code refresh-after}
 * ({@code certification_checked_at}, which only a details fetch stamps - list upserts never touch
 * it; 7 days, as {@code MovieService}'s details TTL), or - for a quality-filtered source - when it was
 * rejected within {@code quality.reject-ttl}. Otherwise {@code details(id)} (which carries
 * release_dates and keywords) is stored by {@link CatalogEnrichmentService#storeDetails} - the same
 * upsert, content-level derivation and checked stamp the enrichment job uses - in its own small
 * transaction. For an unfiltered list source (popular pages) each page's list results are upserted
 * first, so a movie whose details fail still gets a row that {@code CatalogEnrichmentJob} finishes.
 *
 * <p><b>Quality</b> ({@code quality.*}, by default for the export and discover sources only): a
 * movie without poster / runtime / overview or with fewer than {@code min-votes} votes is not stored
 * (an existing row is left as it is), counted {@code rejectedQuality} and recorded in
 * {@code app.catalog_seed_rejects}. Discover list results that already fail (no poster, overview or
 * votes) are rejected without a details request.
 *
 * <p><b>Errors</b>: unknown id - counted {@code notFound} (a cached row is stamped checked, as
 * enrichment does); 5xx / 429 / timeout - retried {@code max-retries} times with exponential
 * backoff, then counted {@code failed}; any other failure storing one movie - counted failed. One
 * movie never stops the run. A rejected token (401) is retried like an outage, but
 * {@code auth-failure-limit} consecutive 401s abort the run. A list page that still fails after its
 * retries, an unreadable export, or a database over {@code max-database-mb} (checked before every
 * page / batch) abort too, keeping the cursors.
 *
 * <p><b>Resume</b>: a resumable (full) run keeps one cursor per source in
 * {@code app.catalog_seed_state} ({@code popular}: next page; {@code export}: next position in the
 * sorted id list, valid only for the same export date; {@code discover}: next year and page, valid
 * only for the same discover settings) and deletes it when that source completes. The skip rule
 * makes everything else idempotent. TMDB's popular order drifts day to day, so a resumed pass is
 * approximate - the nightly top-up and the next full pass catch what moved.
 *
 * <p>Every catalog request (retries included) goes through a {@link RequestPacer}
 * ({@code requests-per-second}; retries, backoff and the 401 limit live in {@link CatalogCalls}, shared
 * with the weekly refresh). One run at a time per process; across processes the callers
 * ({@link CatalogSeedRunner}, {@link CatalogTopUpJob}) hold the {@code CatalogJobLock}.
 */
@Service
public class CatalogSeedService {

    static final String POPULAR_CURSOR = "popular";
    static final String EXPORT_CURSOR = "export";
    static final String DISCOVER_CURSOR = "discover";
    /** Ids per skip-rule query (and per cursor save / size check) for the id file and the export. */
    static final int ID_CHUNK = 200;

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedService.class);

    private final MovieCatalogClient catalog;
    private final CatalogEnrichmentService store;
    private final MovieEnrichmentRepository movies;
    private final CatalogSeedStateRepository state;
    private final CatalogSeedRejectRepository rejects;
    private final TmdbIdExport export;
    private final DatabaseSizeProbe databaseSize;
    private final CatalogSeedProperties configured;
    private final Clock clock;
    private final RequestPacer configuredPacer;
    private CatalogSeedOverrides overrides = CatalogSeedOverrides.NONE;
    // The effective values of the current (or last) run; see applyOverrides().
    private volatile CatalogSeedProperties properties;
    private volatile QualityFilter quality;
    private volatile RequestPacer pacer;
    private final Sleeper sleeper;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public CatalogSeedService(MovieCatalogClient catalog, CatalogEnrichmentService store,
                              MovieEnrichmentRepository movies, CatalogSeedStateRepository state,
                              CatalogSeedRejectRepository rejects, TmdbIdExport export,
                              DatabaseSizeProbe databaseSize, CatalogSeedProperties properties, Clock clock) {
        this(catalog, store, movies, state, rejects, export, databaseSize, properties, clock,
                RequestPacer.system(properties.requestsPerSecond()), Sleeper.SYSTEM);
    }

    CatalogSeedService(MovieCatalogClient catalog, CatalogEnrichmentService store, MovieEnrichmentRepository movies,
                       CatalogSeedStateRepository state, CatalogSeedRejectRepository rejects, TmdbIdExport export,
                       DatabaseSizeProbe databaseSize, CatalogSeedProperties properties, Clock clock,
                       RequestPacer pacer, Sleeper sleeper) {
        this.catalog = catalog;
        this.store = store;
        this.movies = movies;
        this.state = state;
        this.rejects = rejects;
        this.export = export;
        this.databaseSize = databaseSize;
        this.configured = properties;
        this.properties = properties;
        this.quality = new QualityFilter(properties.quality());
        this.clock = clock;
        this.configuredPacer = pacer;
        this.pacer = pacer;
        this.sleeper = sleeper;
    }

    /**
     * What to seed.
     *
     * @param popularPages popular pages 1..n (0 = none; capped at {@link MovieCatalogClient#MAX_PAGE})
     * @param ids          explicit ids, after the popular pages
     * @param resumable    keep the per-source cursors in {@code app.catalog_seed_state}
     * @param export       the TMDB daily id export, after the ids
     * @param discover     discover-by-year, last
     */
    public record SeedPlan(int popularPages, List<Integer> ids, boolean resumable, boolean export, boolean discover) {

        public SeedPlan {
            popularPages = Math.max(0, Math.min(popularPages, MovieCatalogClient.MAX_PAGE));
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        /** Popular pages and ids only. */
        public SeedPlan(int popularPages, List<Integer> ids, boolean resumable) {
            this(popularPages, ids, resumable, false, false);
        }
    }

    /** Runtime overrides (the admin portal's settings); applied at the start of every run. */
    @Autowired(required = false)
    void setOverrides(CatalogSeedOverrides overrides) {
        this.overrides = overrides == null ? CatalogSeedOverrides.NONE : overrides;
    }

    /**
     * Re-reads the overridable values for the run about to start (called under the running flag, so
     * one run sees one consistent set). A changed request rate gets its own pacer; otherwise the
     * configured (or a test's injected) pacer is kept.
     */
    private void applyOverrides() {
        CatalogSeedProperties effective;
        try {
            effective = overrides.apply(configured);
        } catch (RuntimeException ex) {
            log.warn("Catalog setting overrides unavailable, using the configured values: {}", ex.getMessage());
            effective = configured;
        }
        List<String> changed = CatalogSeedOverrides.changes(configured, effective);
        if (!changed.isEmpty()) {
            log.warn("Admin settings (app.admin_settings) override the configured catalog values for this run: {}",
                    String.join(", ", changed));
        }
        this.properties = effective;
        this.quality = new QualityFilter(effective.quality());
        this.pacer = effective.requestsPerSecond().equals(configured.requestsPerSecond()) ? configuredPacer
                : RequestPacer.system(effective.requestsPerSecond());
    }

    /** The full seed: the configured {@code sources}, resumable. */
    public SeedReport seedAll() {
        List<Integer> ids = List.of();
        if (properties.uses(Source.IDS) && properties.idFile() != null) {
            ids = SeedIdFile.read(Path.of(properties.idFile()));
            log.info("Catalog seed: {} ids from {}", ids.size(), properties.idFile());
        }
        return seed(new SeedPlan(properties.uses(Source.POPULAR) ? properties.popularPages() : 0, ids, true,
                properties.uses(Source.EXPORT), properties.uses(Source.DISCOVER)));
    }

    /** The nightly top-up: popular pages 1..{@code top-up.pages}, from page 1 every time. */
    public SeedReport topUp() {
        return topUp(properties.topUp().pages());
    }

    /** A top-up of popular pages 1..{@code pages} (the effective setting; see {@code CatalogRefreshSettings}). */
    public SeedReport topUp(int pages) {
        return seed(new SeedPlan(pages, List.of(), false));
    }

    public SeedReport seed(SeedPlan plan) {
        Instant started = clock.instant();
        if (!running.compareAndSet(false, true)) {
            log.warn("Catalog seed not started: another run is in progress");
            return new Run(started).report(SeedReport.Outcome.ALREADY_RUNNING);
        }
        applyOverrides();
        Run run = new Run(started);
        SeedReport.Outcome outcome = SeedReport.Outcome.COMPLETED;
        try {
            log.info("Catalog seed starting: popular pages {}, {} explicit ids, export {}, discover {}, {} requests/s, "
                            + "max database {}", plan.popularPages(), plan.ids().size(), plan.export(), plan.discover(),
                    properties.requestsPerSecond(),
                    properties.maxDatabaseMb() == 0 ? "off" : properties.maxDatabaseMb() + " MB");
            pruneRejects(plan);
            if (plan.popularPages() > 0) {
                seedPopular(plan, run);
            }
            for (int from = 0; from < plan.ids().size(); from += ID_CHUNK) {
                checkDatabaseSize();
                seedCandidates(plan.ids().subList(from, Math.min(from + ID_CHUNK, plan.ids().size())), Map.of(),
                        Source.IDS, run);
            }
            if (plan.export()) {
                seedExport(plan, run);
            }
            if (plan.discover()) {
                seedDiscover(plan, run);
            }
        } catch (AbortRun | CatalogCalls.Abort ex) {
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

    private void pruneRejects(SeedPlan plan) {
        boolean filtered = (plan.popularPages() > 0 && properties.quality().appliesTo(Source.POPULAR))
                || (!plan.ids().isEmpty() && properties.quality().appliesTo(Source.IDS))
                || (plan.export() && properties.quality().appliesTo(Source.EXPORT))
                || (plan.discover() && properties.quality().appliesTo(Source.DISCOVER));
        if (filtered) {
            int pruned = rejects.pruneBefore(clock.instant().minus(properties.quality().rejectTtl()));
            if (pruned > 0) {
                log.info("Catalog seed: {} expired quality rejections dropped", pruned);
            }
        }
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
            checkDatabaseSize();
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
            seedListPage(result, Source.POPULAR, run);
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

    /**
     * The export's ids in chunks. The cursor (next 1-based position) is tied to the export date: a new
     * day's file has a different order, so an old cursor is dropped - the skip rule still saves every
     * already-seeded id the details request.
     */
    private void seedExport(SeedPlan plan, Run run) throws InterruptedException {
        checkDatabaseSize();
        TmdbIdExport.Ids loaded;
        try {
            loaded = export.load(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (IOException | TmdbIdExport.ExportUnavailableException ex) {
            throw new AbortRun("TMDB id export could not be read: " + ex.getMessage());
        }
        String version = loaded.date().toString();
        int start = 0;
        if (plan.resumable()) {
            Optional<Cursor> cursor = state.cursor(EXPORT_CURSOR);
            if (cursor.isPresent() && version.equals(cursor.get().version())
                    && cursor.get().next() <= loaded.size() + 1) {
                start = cursor.get().next() - 1;
                log.info("Catalog seed resuming the {} export at position {} of {}", version, start + 1,
                        loaded.size());
            } else if (cursor.isPresent()) {
                log.info("Catalog seed: export cursor for {} does not match the {} export; starting over "
                        + "(already-seeded ids are skipped)", cursor.get().version(), version);
            }
        }
        int[] ids = loaded.ids();
        for (int from = start; from < ids.length; from += ID_CHUNK) {
            checkDatabaseSize();
            int to = Math.min(from + ID_CHUNK, ids.length);
            List<Integer> chunk = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                chunk.add(ids[i]);
            }
            seedCandidates(chunk, Map.of(), Source.EXPORT, run);
            if (plan.resumable()) {
                state.save(EXPORT_CURSOR, to + 1, version);
            }
        }
        if (plan.resumable()) {
            state.clear(EXPORT_CURSOR);
        }
    }

    /** {@code discover.from-year}..{@code to-year} (default this year), {@code pages-per-year} pages each. */
    private void seedDiscover(SeedPlan plan, Run run) throws InterruptedException {
        CatalogSeedProperties.Discover settings = properties.discover();
        int lastYear = settings.toYear() != null ? settings.toYear()
                : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).getYear();
        String version = settings.fromYear() + ".." + lastYear + "/" + settings.pagesPerYear() + "/"
                + settings.minVotes();
        int startYear = settings.fromYear();
        int startPage = 1;
        if (plan.resumable()) {
            Optional<Cursor> cursor = state.cursor(DISCOVER_CURSOR);
            if (cursor.isPresent() && version.equals(cursor.get().version())) {
                int year = cursor.get().next() / 1000;
                int page = cursor.get().next() % 1000;
                if (year >= settings.fromYear() && year <= lastYear && page >= 1 && page <= settings.pagesPerYear()) {
                    startYear = year;
                    startPage = page;
                    log.info("Catalog seed resuming discover at {} page {}", year, page);
                }
            } else if (cursor.isPresent()) {
                log.info("Catalog seed: discover settings changed ({} -> {}); starting over", cursor.get().version(),
                        version);
            }
        }
        for (int year = startYear; year <= lastYear; year++) {
            for (int page = year == startYear ? startPage : 1; page <= settings.pagesPerYear(); page++) {
                checkDatabaseSize();
                int y = year;
                int p = page;
                CatalogPage result;
                try {
                    result = call(run, () -> catalog.discoverByYear(y, settings.minVotes(), p));
                } catch (NotFoundException ex) {
                    break;
                } catch (UpstreamUnavailableException ex) {
                    throw new AbortRun("discover " + year + " page " + page + " still failing after "
                            + properties.maxRetries() + " retries; the next run resumes here");
                } catch (UnsupportedOperationException ex) {
                    throw new AbortRun("this catalog client cannot discover by year");
                }
                run.pages++;
                seedListPage(result, Source.DISCOVER, run);
                boolean lastPage = result.movies().isEmpty() || !result.hasNext() || page == settings.pagesPerYear();
                if (plan.resumable()) {
                    state.save(DISCOVER_CURSOR, lastPage ? (year + 1) * 1000 + 1 : year * 1000 + page + 1, version);
                }
                if (lastPage) {
                    break;
                }
            }
        }
        if (plan.resumable()) {
            state.clear(DISCOVER_CURSOR);
        }
    }

    /**
     * One list page: an unfiltered source caches the list results first (so failed details still
     * reach the enrichment queue); a filtered one does not - those rows may fail the bar.
     */
    private void seedListPage(CatalogPage result, Source source, Run run) throws InterruptedException {
        boolean filtered = properties.quality().appliesTo(source);
        if (!filtered) {
            store.storeListResults(result.movies());
        }
        Map<Integer, CatalogMovie> listed = new HashMap<>();
        result.movies().forEach(movie -> listed.putIfAbsent(movie.id(), movie));
        seedCandidates(result.movies().stream().map(CatalogMovie::id).toList(), listed, source, run);
    }

    /**
     * @param listed list results by id (empty for id sources), used to reject a filtered source's
     *               obvious misses without a details request
     */
    private void seedCandidates(List<Integer> ids, Map<Integer, CatalogMovie> listed, Source source, Run run)
            throws InterruptedException {
        List<Integer> fresh = new ArrayList<>();
        for (Integer id : ids) {
            if (run.seen.add(id)) {
                fresh.add(id);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        boolean filtered = properties.quality().appliesTo(source);
        Set<Integer> seeded = movies.checkedSince(fresh, now.minus(properties.refreshAfter()));
        Set<Integer> rejected = Set.of();
        if (filtered) {
            List<Integer> unseeded = fresh.stream().filter(id -> !seeded.contains(id)).toList();
            rejected = rejects.rejectedSince(unseeded, now.minus(properties.quality().rejectTtl()));
        }
        for (Integer id : fresh) {
            run.candidates++;
            if (seeded.contains(id)) {
                run.skipped++;
            } else if (rejected.contains(id)) {
                run.skippedRejected++;
            } else if (filtered && listed.containsKey(id) && !quality.listFailures(listed.get(id)).isEmpty()) {
                reject(id, quality.listFailures(listed.get(id)), run);
            } else {
                seedOne(id, filtered, run);
            }
            if (run.candidates % properties.progressEvery() == 0) {
                log.info("Catalog seed progress: {} candidates, {} fetched, {} already seeded, {} not found, "
                                + "{} failed, {} rejected (quality), {} skipped (rejected earlier), {} pages",
                        run.candidates, run.fetched, run.skipped, run.notFound, run.failed, run.rejectedQuality,
                        run.skippedRejected, run.pages);
            }
        }
    }

    private void seedOne(int id, boolean filtered, Run run) throws InterruptedException {
        try {
            CatalogMovie details = call(run, () -> catalog.details(id));
            List<String> failures = filtered ? quality.failures(details) : List.of();
            if (!failures.isEmpty()) {
                reject(id, failures, run);
                return;
            }
            store.storeDetails(details);
            run.fetched++;
        } catch (NotFoundException ex) {
            store.markChecked(id);
            run.notFound++;
        } catch (AbortRun | CatalogCalls.Abort ex) {
            throw ex;
        } catch (UpstreamUnavailableException ex) {
            log.debug("Catalog seed: movie {} still failing after retries", id);
            run.failed++;
        } catch (RuntimeException ex) {
            log.warn("Catalog seed: could not store movie {}", id, ex);
            run.failed++;
        }
    }

    /** Not stored (an existing row is left as it is); remembered so re-runs skip it for reject-ttl. */
    private void reject(int id, List<String> failures, Run run) {
        try {
            rejects.record(id, clock.instant());
            run.rejectedQuality++;
            log.debug("Catalog seed: movie {} rejected (missing {})", id, failures);
        } catch (RuntimeException ex) {
            log.warn("Catalog seed: could not record the rejection of movie {}", id, ex);
            run.failed++;
        }
    }

    /** The {@code max-database-mb} guard, before every page / batch. */
    private void checkDatabaseSize() {
        CatalogCalls.checkDatabaseSize(databaseSize, properties.maxDatabaseMb());
    }

    /** One paced catalog request with retries ({@link CatalogCalls}). */
    private <T> T call(Run run, Supplier<T> request) throws InterruptedException {
        return run.calls.call(request);
    }

    Duration backoff(int attempt) {
        return CatalogCalls.backoff(properties.initialBackoff(), attempt);
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
        int rejectedQuality;
        int skippedRejected;
        final CatalogCalls calls = new CatalogCalls(pacer, sleeper, properties);

        Run(Instant started) {
            this.started = started;
        }

        SeedReport report(SeedReport.Outcome outcome) {
            return new SeedReport(outcome, pages, candidates, fetched, skipped, notFound, failed, rejectedQuality,
                    skippedRejected, Duration.between(started, clock.instant()));
        }
    }
}
