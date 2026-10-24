package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.ChangedIdsPage;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogRefreshProperties.Step;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The weekly catalog refresh: keeps what is stored fresh in tiers. Plumbing only - it decides what
 * to re-fetch, never how movies rank. Steps, in order (each switchable, see
 * {@link CatalogRefreshProperties}):
 * <ol>
 *   <li><b>Main tier</b>: the top {@code main-tier-size} stored movies by {@code app.movies.popularity}
 *       (which this pass itself refreshes) get their details re-fetched through
 *       {@link CatalogEnrichmentService#storeDetails} - rating, vote count, certification, keywords,
 *       poster, content levels. Not quality-filtered: an existing row is never deleted.</li>
 *   <li><b>New releases</b>: discover-by-year for this year and last year
 *       ({@code new-release-pages-per-year} pages each, most votes first), quality-filtered like the
 *       seeder's discover source: failures are not stored and are recorded as rejects.</li>
 *   <li><b>Rejected re-check</b>: up to {@code reject-recheck-limit} ids in
 *       {@code app.catalog_seed_rejects} older than {@code quality.reject-ttl} are fetched again -
 *       stored (and un-rejected) if they pass now, otherwise re-stamped.</li>
 *   <li><b>Long-tail changes</b>: TMDB's change list for the last {@code changes-days} days; ids we
 *       store that are not in the main tier get their details re-fetched.</li>
 * </ol>
 * Every step skips movies whose details were fetched within {@code refresh-after} (6 days: the weekly
 * cadence always refreshes, a re-run the same week does not) and ids another step already handled.
 *
 * <p>Request discipline is the seeder's ({@link CatalogCalls}: pacer, retries with backoff, a run
 * aborted by repeated 401s), as is the {@code max-database-mb} guard, checked before every chunk and
 * page. A list page still failing after its retries ends that step (counted failed); after
 * {@code max-consecutive-failures} movies in a row fail, the catalog is considered down and the run
 * is ABORTED. One run at a time per process; across processes the caller holds the
 * {@code CatalogJobLock}.
 */
@Service
public class CatalogRefreshService {

    static final int CHUNK = 200;

    private static final Logger log = LoggerFactory.getLogger(CatalogRefreshService.class);

    private final MovieCatalogClient catalog;
    private final CatalogEnrichmentService store;
    private final MovieEnrichmentRepository movies;
    private final CatalogSeedRejectRepository rejects;
    private final DatabaseSizeProbe databaseSize;
    private final CatalogSeedProperties configured;
    private final Clock clock;
    private final RequestPacer configuredPacer;
    private CatalogSeedOverrides overrides = CatalogSeedOverrides.NONE;
    // The effective values of the current (or last) run; see applyOverrides().
    private volatile CatalogSeedProperties seedProperties;
    private volatile QualityFilter quality;
    private volatile RequestPacer pacer;
    private final Sleeper sleeper;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public CatalogRefreshService(MovieCatalogClient catalog, CatalogEnrichmentService store,
                                 MovieEnrichmentRepository movies, CatalogSeedRejectRepository rejects,
                                 DatabaseSizeProbe databaseSize, CatalogSeedProperties seedProperties, Clock clock) {
        this(catalog, store, movies, rejects, databaseSize, seedProperties, clock,
                RequestPacer.system(seedProperties.requestsPerSecond()), Sleeper.SYSTEM);
    }

    CatalogRefreshService(MovieCatalogClient catalog, CatalogEnrichmentService store, MovieEnrichmentRepository movies,
                          CatalogSeedRejectRepository rejects, DatabaseSizeProbe databaseSize,
                          CatalogSeedProperties seedProperties, Clock clock, RequestPacer pacer, Sleeper sleeper) {
        this.catalog = catalog;
        this.store = store;
        this.movies = movies;
        this.rejects = rejects;
        this.databaseSize = databaseSize;
        this.configured = seedProperties;
        this.seedProperties = seedProperties;
        this.quality = new QualityFilter(seedProperties.quality());
        this.clock = clock;
        this.configuredPacer = pacer;
        this.pacer = pacer;
        this.sleeper = sleeper;
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
        this.seedProperties = effective;
        this.quality = new QualityFilter(effective.quality());
        this.pacer = effective.requestsPerSecond().equals(configured.requestsPerSecond()) ? configuredPacer
                : RequestPacer.system(effective.requestsPerSecond());
    }

    public RefreshReport refresh(Settings settings) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Catalog refresh not started: another run is in progress");
            return RefreshReport.notStarted(Outcome.ALREADY_RUNNING);
        }
        applyOverrides();
        Run run = new Run(settings, clock.instant());
        Outcome outcome = Outcome.COMPLETED;
        String abortReason = null;
        try {
            log.info("Catalog refresh starting: steps {}, main tier {}, new releases {} pages/year, changes {} days, "
                            + "refresh after {}, {} requests/s, max database {}", settings.steps(),
                    settings.mainTierSize(), settings.newReleasePagesPerYear(), settings.changesDays(),
                    settings.refreshAfter(), seedProperties.requestsPerSecond(),
                    seedProperties.maxDatabaseMb() == 0 ? "off" : seedProperties.maxDatabaseMb() + " MB");
            if (settings.runs(Step.MAIN_TIER)) {
                mainTier(run);
            }
            if (settings.runs(Step.NEW_RELEASES)) {
                newReleases(run);
            }
            if (settings.runs(Step.REJECTS)) {
                recheckRejects(run);
            }
            if (settings.runs(Step.CHANGES)) {
                changes(run);
            }
        } catch (CatalogCalls.Abort ex) {
            log.error("Catalog refresh aborted: {}", ex.getMessage());
            outcome = Outcome.ABORTED;
            abortReason = ex.getMessage();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Catalog refresh interrupted");
            outcome = Outcome.INTERRUPTED;
        } finally {
            running.set(false);
        }
        RefreshReport report = new RefreshReport(outcome, abortReason, run.mainTier.freeze(),
                run.newReleases.freeze(), run.rejects.freeze(), run.changes.freeze(),
                Duration.between(run.started, clock.instant()));
        log.info(report.summary());
        return report;
    }

    /** Step 1: re-fetch the top {@code main-tier-size} stored movies, whatever their quality. */
    private void mainTier(Run run) throws InterruptedException {
        List<Integer> tier = movies.topByPopularity(run.settings.mainTierSize());
        run.mainTierIds.addAll(tier);
        Counter counts = run.mainTier;
        counts.listed = tier.size();
        for (int from = 0; from < tier.size(); from += CHUNK) {
            checkDatabaseSize();
            List<Integer> chunk = fresh(run, tier.subList(from, Math.min(from + CHUNK, tier.size())));
            Set<Integer> recent = movies.checkedSince(chunk, run.freshSince);
            for (Integer id : chunk) {
                counts.candidates++;
                if (recent.contains(id)) {
                    counts.skippedFresh++;
                } else {
                    refreshStored(id, counts, run);
                }
                progress(run);
            }
        }
    }

    /** Step 2: this year's and last year's releases, most votes first, quality-filtered. */
    private void newReleases(Run run) throws InterruptedException {
        int thisYear = LocalDate.ofInstant(run.started, ZoneOffset.UTC).getYear();
        Counter counts = run.newReleases;
        for (int year = thisYear - 1; year <= thisYear; year++) {
            for (int page = 1; page <= run.settings.newReleasePagesPerYear(); page++) {
                checkDatabaseSize();
                int y = year;
                int p = page;
                CatalogPage result;
                try {
                    result = run.calls.call(() -> catalog.discoverByYear(y, run.settings.newReleaseMinVotes(), p));
                } catch (NotFoundException ex) {
                    break;
                } catch (UpstreamUnavailableException ex) {
                    log.warn("Catalog refresh: discover {} page {} still failing after retries; skipping the rest "
                            + "of {}", year, page, year);
                    counts.failed++;
                    break;
                } catch (UnsupportedOperationException ex) {
                    throw new CatalogCalls.Abort("this catalog client cannot discover by year");
                }
                counts.pages++;
                counts.listed += result.movies().size();
                Map<Integer, CatalogMovie> listed = new HashMap<>();
                result.movies().forEach(movie -> listed.putIfAbsent(movie.id(), movie));
                newCandidates(fresh(run, result.movies().stream().map(CatalogMovie::id).toList()), listed, run);
                if (result.movies().isEmpty() || !result.hasNext()) {
                    break;
                }
            }
        }
    }

    private void newCandidates(List<Integer> ids, Map<Integer, CatalogMovie> listed, Run run)
            throws InterruptedException {
        if (ids.isEmpty()) {
            return;
        }
        Counter counts = run.newReleases;
        boolean filtered = seedProperties.quality().enabled();
        Set<Integer> recent = movies.checkedSince(ids, run.freshSince);
        Set<Integer> rejected = filtered
                ? rejects.rejectedSince(ids.stream().filter(id -> !recent.contains(id)).toList(), run.rejectedSince)
                : Set.of();
        for (Integer id : ids) {
            counts.candidates++;
            if (recent.contains(id)) {
                counts.skippedFresh++;
            } else if (rejected.contains(id)) {
                counts.skippedRejected++;
            } else if (filtered && !quality.listFailures(listed.get(id)).isEmpty()) {
                reject(id, counts);
            } else {
                fetchFiltered(id, filtered, counts, run);
            }
            progress(run);
        }
    }

    /** Step 3: expired rejects get another look. */
    private void recheckRejects(Run run) throws InterruptedException {
        Counter counts = run.rejects;
        List<Integer> due = rejects.rejectedBefore(run.rejectedSince, run.settings.rejectRecheckLimit());
        counts.listed = due.size();
        boolean filtered = seedProperties.quality().enabled();
        for (int from = 0; from < due.size(); from += CHUNK) {
            checkDatabaseSize();
            for (Integer id : fresh(run, due.subList(from, Math.min(from + CHUNK, due.size())))) {
                counts.candidates++;
                try {
                    CatalogMovie details = run.calls.call(() -> catalog.details(id));
                    run.consecutiveFailures = 0;
                    if (filtered && !quality.failures(details).isEmpty()) {
                        reject(id, counts);
                    } else {
                        store.storeDetails(details);
                        rejects.delete(id);
                        counts.fetched++;
                    }
                } catch (NotFoundException ex) {
                    run.consecutiveFailures = 0;
                    rejects.delete(id);
                    counts.notFound++;
                } catch (CatalogCalls.Abort ex) {
                    throw ex;
                } catch (UpstreamUnavailableException ex) {
                    failed(id, counts, run);
                } catch (RuntimeException ex) {
                    log.warn("Catalog refresh: could not re-check rejected movie {}", id, ex);
                    counts.failed++;
                }
                progress(run);
            }
        }
    }

    /** Step 4: TMDB's change list; stored movies outside the main tier are re-fetched. */
    private void changes(Run run) throws InterruptedException {
        Counter counts = run.changes;
        LocalDate to = LocalDate.ofInstant(run.started, ZoneOffset.UTC);
        LocalDate from = to.minusDays(run.settings.changesDays());
        for (int page = 1; page <= run.settings.changesMaxPages(); page++) {
            checkDatabaseSize();
            int p = page;
            ChangedIdsPage result;
            try {
                result = run.calls.call(() -> catalog.changedMovieIds(from, to, p));
            } catch (NotFoundException ex) {
                break;
            } catch (UpstreamUnavailableException ex) {
                log.warn("Catalog refresh: change list page {} still failing after retries; stopping the step", page);
                counts.failed++;
                break;
            } catch (UnsupportedOperationException ex) {
                throw new CatalogCalls.Abort("this catalog client cannot list changes");
            }
            counts.pages++;
            counts.listed += result.ids().size();
            List<Integer> outsideTier = result.ids().stream().filter(id -> !run.mainTierIds.contains(id)).toList();
            Set<Integer> stored = movies.storedAmong(outsideTier);
            List<Integer> candidates = fresh(run, outsideTier.stream().filter(stored::contains).toList());
            Set<Integer> recent = movies.checkedSince(candidates, run.freshSince);
            for (Integer id : candidates) {
                counts.candidates++;
                if (recent.contains(id)) {
                    counts.skippedFresh++;
                } else {
                    refreshStored(id, counts, run);
                }
                progress(run);
            }
            if (result.ids().isEmpty() || !result.hasNext()) {
                break;
            }
        }
    }

    /** Re-fetches a stored movie: never quality-filtered, never deleted. */
    private void refreshStored(int id, Counter counts, Run run) throws InterruptedException {
        try {
            CatalogMovie details = run.calls.call(() -> catalog.details(id));
            run.consecutiveFailures = 0;
            store.storeDetails(details);
            counts.fetched++;
        } catch (NotFoundException ex) {
            run.consecutiveFailures = 0;
            store.markChecked(id);
            counts.notFound++;
        } catch (CatalogCalls.Abort ex) {
            throw ex;
        } catch (UpstreamUnavailableException ex) {
            failed(id, counts, run);
        } catch (RuntimeException ex) {
            log.warn("Catalog refresh: could not store movie {}", id, ex);
            counts.failed++;
        }
    }

    /** A new movie: stored only if it passes the bar (when filtered); an existing row is left as it is. */
    private void fetchFiltered(int id, boolean filtered, Counter counts, Run run) throws InterruptedException {
        try {
            CatalogMovie details = run.calls.call(() -> catalog.details(id));
            run.consecutiveFailures = 0;
            if (filtered && !quality.failures(details).isEmpty()) {
                reject(id, counts);
                return;
            }
            store.storeDetails(details);
            counts.fetched++;
        } catch (NotFoundException ex) {
            run.consecutiveFailures = 0;
            store.markChecked(id);
            counts.notFound++;
        } catch (CatalogCalls.Abort ex) {
            throw ex;
        } catch (UpstreamUnavailableException ex) {
            failed(id, counts, run);
        } catch (RuntimeException ex) {
            log.warn("Catalog refresh: could not store movie {}", id, ex);
            counts.failed++;
        }
    }

    private void failed(int id, Counter counts, Run run) {
        log.debug("Catalog refresh: movie {} still failing after retries", id);
        counts.failed++;
        if (++run.consecutiveFailures >= run.settings.maxConsecutiveFailures()) {
            throw new CatalogCalls.Abort(run.consecutiveFailures + " movies in a row still failing after "
                    + run.calls.maxRetries() + " retries - the catalog looks unavailable");
        }
    }

    private void reject(int id, Counter counts) {
        try {
            rejects.record(id, clock.instant());
            counts.rejectedQuality++;
        } catch (RuntimeException ex) {
            log.warn("Catalog refresh: could not record the rejection of movie {}", id, ex);
            counts.failed++;
        }
    }

    /** The ids no earlier step of this run handled, in order. */
    private static List<Integer> fresh(Run run, List<Integer> ids) {
        List<Integer> fresh = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            if (run.seen.add(id)) {
                fresh.add(id);
            }
        }
        return fresh;
    }

    private void checkDatabaseSize() {
        CatalogCalls.checkDatabaseSize(databaseSize, seedProperties.maxDatabaseMb());
    }

    private void progress(Run run) {
        if (++run.considered % seedProperties.progressEvery() == 0) {
            log.info("Catalog refresh progress: {} considered | main tier: {} | new releases: {} | rejects: {} | "
                            + "changes: {}", run.considered, run.mainTier.freeze().line(),
                    run.newReleases.freeze().line(), run.rejects.freeze().line(), run.changes.freeze().line());
        }
    }

    private static final class Counter {
        int pages;
        int listed;
        int candidates;
        int fetched;
        int skippedFresh;
        int notFound;
        int failed;
        int rejectedQuality;
        int skippedRejected;

        RefreshReport.Step freeze() {
            return new RefreshReport.Step(pages, listed, candidates, fetched, skippedFresh, notFound, failed,
                    rejectedQuality, skippedRejected);
        }
    }

    private final class Run {
        final Settings settings;
        final Instant started;
        final Instant freshSince;
        final Instant rejectedSince;
        final CatalogCalls calls = new CatalogCalls(pacer, sleeper, seedProperties);
        final Set<Integer> seen = new HashSet<>();
        final Set<Integer> mainTierIds = new HashSet<>();
        final Counter mainTier = new Counter();
        final Counter newReleases = new Counter();
        final Counter rejects = new Counter();
        final Counter changes = new Counter();
        int consecutiveFailures;
        int considered;

        Run(Settings settings, Instant started) {
            this.settings = settings;
            this.started = started;
            this.freshSince = started.minus(settings.refreshAfter());
            this.rejectedSince = started.minus(seedProperties.quality().rejectTtl());
        }
    }
}
