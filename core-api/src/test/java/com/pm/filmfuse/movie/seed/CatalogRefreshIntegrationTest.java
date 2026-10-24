package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.job.JobRunRecorder;
import com.pm.filmfuse.common.job.JobRunRepository;
import com.pm.filmfuse.common.job.JobRunRepository.JobRun;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.ChangedIdsPage;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings.Settings;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly refresh against the real schema, over a synthetic catalog with random ids far above real
 * TMDB ids: {@value #TIER} main-tier movies (stored, stale, and more popular than anything else in the
 * shared test database), {@value #TAIL} stored long-tail movies, {@value #NEW} new releases of this
 * year (one without a poster), and two quality rejects from 40 days ago (one passing now, one still
 * failing). Every row it creates (movies, content levels, rejects, job runs) is deleted afterwards.
 */
class CatalogRefreshIntegrationTest extends AbstractIntegrationTest {

    private static final int TIER = 5;
    private static final int TAIL = 3;
    private static final int NEW = 4;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CatalogEnrichmentService store;
    @Autowired
    private MovieEnrichmentRepository movies;
    @Autowired
    private CatalogSeedRejectRepository rejects;
    @Autowired
    private CatalogJobLock lock;
    @Autowired
    private JobRunRecorder recorder;
    @Autowired
    private JobRunRepository runs;
    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    private final int year = LocalDate.now(ZoneOffset.UTC).getYear();
    private final List<String> calls = new ArrayList<>();
    private Instant testStart;
    private int base;
    private List<Integer> all;
    private FixtureMovieCatalogClient fixture;

    private int tier(int i) {
        return base + i;
    }

    private int tail(int i) {
        return base + 100 + i;
    }

    private int fresh(int i) {
        return base + 200 + i;
    }

    private int passingReject() {
        return base + 300;
    }

    private int failingReject() {
        return base + 301;
    }

    private static CatalogMovie details(int id, String title, int releaseYear, Integer runtime, String poster,
                                        double popularity, Integer votes) {
        return new CatalogMovie(id, title, releaseYear, runtime, poster, null, "Overview " + id, List.of(18),
                popularity, 7.5, "PG-13", List.of("murder"), votes);
    }

    @BeforeEach
    void syntheticCatalog() {
        testStart = Instant.now().minusSeconds(1);
        base = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE - 1_000);
        List<CatalogMovie> catalog = new ArrayList<>();
        List<CatalogMovie> stale = new ArrayList<>();
        for (int i = 0; i < TIER; i++) {
            double popularity = 1e15 + i;
            catalog.add(details(tier(i), "Tier " + i, 1990, 100, "/t" + i + ".jpg", popularity, 5_000 + i));
            stale.add(new CatalogMovie(tier(i), "Stale tier " + i, 1990, null, null, null, null, List.of(), popularity,
                    5.0));
        }
        for (int i = 0; i < TAIL; i++) {
            catalog.add(details(tail(i), "Tail " + i, 1980, 90, null, 1e-9, 1));
            stale.add(new CatalogMovie(tail(i), "Stale tail " + i, 1980, null, null, null, null, List.of(), 1e-9, 5.0));
        }
        for (int i = 0; i < NEW; i++) {
            catalog.add(details(fresh(i), "New " + i, year, 95, i == 0 ? null : "/n" + i + ".jpg", 5.0, 50 + i));
        }
        catalog.add(details(passingReject(), "Better now", 2001, 101, "/r.jpg", 1e-9, 40));
        catalog.add(details(failingReject(), "Still short", 2001, null, "/r.jpg", 1e-9, 40));
        fixture = new FixtureMovieCatalogClient(catalog);
        all = catalog.stream().map(CatalogMovie::id).toList();
        store.storeListResults(stale);
        Instant longAgo = Instant.now().minus(Duration.ofDays(40));
        rejects.record(passingReject(), longAgo);
        rejects.record(failingReject(), longAgo);
    }

    @AfterEach
    void removeSyntheticRows() {
        Integer[] ids = all.toArray(Integer[]::new);
        jdbc.update("delete from app.movies where id = any(?)", (Object) ids);
        jdbc.update("delete from app.catalog_seed_rejects where movie_id = any(?)", (Object) ids);
        jdbc.update("delete from app.job_runs where job_name = ? and started_at >= ?", JobNames.CATALOG_REFRESH,
                Timestamp.from(testStart));
    }

    private final MovieCatalogClient recording = new MovieCatalogClient() {
        @Override
        public CatalogPage search(String query, int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CatalogPage popular(Integer genreId, int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CatalogPage discoverByYear(int y, int minVoteCount, int page) {
            calls.add("y" + y + "p" + page);
            return fixture.discoverByYear(y, minVoteCount, page);
        }

        @Override
        public ChangedIdsPage changedMovieIds(LocalDate from, LocalDate to, int page) {
            calls.add("c" + page);
            return fixture.changedMovieIds(from, to, page);
        }

        @Override
        public CatalogMovie details(int id) {
            calls.add("d" + id);
            return fixture.details(id);
        }
    };

    private CatalogRefreshService service() {
        CatalogSeedProperties properties = new CatalogSeedProperties(null, 0, null, 1000d, 0, Duration.ZERO, null, 1,
                null, null, null, 0, null, null, null);
        return new CatalogRefreshService(recording, store, movies, rejects, () -> 0L, properties, Clock.systemUTC(),
                new RequestPacer(1000, System::nanoTime, duration -> { }), duration -> { });
    }

    private CatalogRefreshSettings settings() {
        Settings defaults = Settings.of(new CatalogSeedProperties.TopUp(null, null, null),
                CatalogRefreshProperties.defaults());
        Settings settings = new Settings(true, defaults.topUpCron(), defaults.topUpPages(), true,
                defaults.refreshCron(), defaults.steps(), TIER, defaults.refreshAfter(), 2, 5, 100, 7, 10, 25);
        return () -> settings;
    }

    private CatalogRefreshJob job() {
        return new CatalogRefreshJob(service(), settings(), lock, recorder);
    }

    private String title(int id) {
        return jdbc.queryForObject("select title from app.movies where id = ?", String.class, id);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, (Object) all.toArray(Integer[]::new));
    }

    @Test
    void theWeeklyRefreshWorksThroughEveryTierAndIsRecorded() {
        RefreshReport report = job().runNow("admin:test");

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        // Main tier: the 5 most popular stored movies in the whole database are ours.
        assertThat(report.mainTier().fetched()).isEqualTo(TIER);
        IntStream.range(0, TIER).forEach(i -> assertThat(title(tier(i))).isEqualTo("Tier " + i));
        assertThat(count("select count(*) from app.movies where id = any(?) and certification = 'PG-13' "
                + "and vote_count >= 5000 and certification_checked_at is not null")).isEqualTo(TIER);
        assertThat(count("select count(*) from app.movie_content_levels where movie_id = any(?) "
                + "and dimension = 'violence'")).isPositive();
        // New releases: three stored; the poster-less one rejected from its list result, without details.
        assertThat(report.newReleases().fetched()).isEqualTo(NEW - 1);
        assertThat(report.newReleases().rejectedQuality()).isEqualTo(1);
        assertThat(calls).doesNotContain("d" + fresh(0));
        assertThat(jdbc.queryForObject("select count(*) from app.movies where id = ?", Long.class, fresh(0))).isZero();
        assertThat(rejects.rejectedSince(List.of(fresh(0)), testStart)).containsExactly(fresh(0));
        // Rejects: one passes now (stored, forgotten), one still fails (re-stamped).
        assertThat(report.rejects().fetched()).isEqualTo(1);
        assertThat(report.rejects().rejectedQuality()).isEqualTo(1);
        assertThat(title(passingReject())).isEqualTo("Better now");
        assertThat(rejects.rejectedSince(List.of(passingReject()), Instant.EPOCH)).isEmpty();
        assertThat(rejects.rejectedSince(List.of(failingReject()), testStart)).containsExactly(failingReject());
        // Changes: every synthetic id changed; the stored long tail (poster-less, 1 vote) is refreshed, not deleted.
        assertThat(report.changes().fetched()).isEqualTo(TAIL);
        IntStream.range(0, TAIL).forEach(i -> assertThat(title(tail(i))).isEqualTo("Tail " + i));
        assertThat(calls).contains("y" + (year - 1) + "p1", "y" + year + "p1", "c1");

        List<JobRun> history = runs.latest(JobNames.CATALOG_REFRESH, 1);
        assertThat(history).singleElement().satisfies(run -> {
            assertThat(run.outcome()).isEqualTo(JobOutcome.COMPLETED);
            assertThat(run.triggeredBy()).isEqualTo("admin:test");
            assertThat(run.summary().get("mainTier")).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) run.summary().get("mainTier")).get("fetched")).isEqualTo(TIER);
            assertThat(((Map<?, ?>) run.summary().get("changes")).get("fetched")).isEqualTo(TAIL);
        });
    }

    @Test
    void aSecondRunTheSameWeekFetchesNothingAgain() {
        job().runNow(null);
        calls.clear();

        RefreshReport again = job().runNow(null);

        assertThat(again.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(again.totalFetched()).isZero();
        assertThat(again.mainTier().skippedFresh()).isEqualTo(TIER);
        // The long tail plus the reject stored last run (this time no earlier step handled it).
        assertThat(again.changes().skippedFresh()).isEqualTo(TAIL + 1);
        assertThat(again.newReleases().skippedFresh()).isEqualTo(NEW - 1);
        assertThat(again.newReleases().skippedRejected()).isEqualTo(1);
        assertThat(calls).noneMatch(call -> call.startsWith("d"));
    }

    @Test
    void whileAnotherProcessHoldsTheCatalogLockTheRefreshSkipsAndSaysSo() throws Exception {
        try (Connection seedCli = DriverManager.getConnection(url, username, password)) {
            try (PreparedStatement statement = seedCli.prepareStatement("select pg_advisory_lock(?)")) {
                statement.setLong(1, CatalogJobLock.KEY);
                statement.execute();
            }

            RefreshReport report = job().runNow("admin:test");

            assertThat(report.outcome()).isEqualTo(Outcome.ALREADY_RUNNING);
            assertThat(calls).isEmpty();
            assertThat(runs.latest(JobNames.CATALOG_REFRESH, 1)).singleElement()
                    .satisfies(run -> assertThat(run.outcome()).isEqualTo(JobOutcome.SKIPPED));
            try (PreparedStatement statement = seedCli.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, CatalogJobLock.KEY);
                statement.execute();
            }
        }
        assertThat(lock.isHeld()).isFalse();
        assertThat(job().runNow(null).outcome()).isEqualTo(Outcome.COMPLETED);
    }
}
