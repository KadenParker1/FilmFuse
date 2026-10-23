package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogSeedService.SeedPlan;
import com.pm.filmfuse.movie.seed.CatalogSeedStateRepository.Cursor;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.MutableClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulk sources against the real schema (V19): a synthetic export of {@value #EXPORTED} ids, of
 * which the first {@value #KNOWN} exist in a synthetic fixture catalog (every third without a
 * poster, so it fails the quality bar) and the rest are unknown. Ids are random and far above real
 * TMDB ids; every row this test creates (movies, rejects, cursors) is deleted afterwards.
 */
class CatalogBulkSeedIntegrationTest extends AbstractIntegrationTest {

    private static final int KNOWN = 30;
    private static final int EXPORTED = 250;
    private static final int YEAR = 2001;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CatalogEnrichmentService store;
    @Autowired
    private MovieEnrichmentRepository movies;
    @Autowired
    private CatalogSeedStateRepository state;
    @Autowired
    private CatalogSeedRejectRepository rejects;

    private final MutableClock clock = new MutableClock();
    private List<Integer> ids;
    private FixtureMovieCatalogClient fixture;
    private final List<String> calls = new ArrayList<>();
    private byte[] exportFile;

    @BeforeEach
    void syntheticCatalogAndExport() throws IOException {
        jdbc.update("delete from app.catalog_seed_state");
        int genre = ThreadLocalRandom.current().nextInt(1_000_000, 900_000_000);
        int base = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE - 1_000);
        ids = IntStream.range(0, EXPORTED).map(i -> base + i).boxed().toList();
        fixture = new FixtureMovieCatalogClient(IntStream.range(0, KNOWN).mapToObj(i -> new CatalogMovie(
                ids.get(i), "Bulk " + i, YEAR, 90 + i, rejected(i) ? null : "/bulk" + i + ".jpg", null, "Overview " + i,
                List.of(genre), (KNOWN - i) * 1e-9, 6.5, "PG", List.of("friendship"), 100 + i)).toList());
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < EXPORTED; i++) {
            // Export popularity falls with the index, so the sorted list is ids in index order.
            lines.append("{\"adult\":false,\"id\":").append(ids.get(i)).append(",\"original_title\":\"x\",")
                    .append("\"popularity\":").append(EXPORTED - i).append(",\"video\":false}\n");
        }
        lines.append("{\"adult\":true,\"id\":").append(base - 1).append(",\"popularity\":9999,\"video\":false}\n");
        lines.append("garbage\n");
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
        }
        exportFile = gz.toByteArray();
    }

    private static boolean rejected(int index) {
        return index % 3 == 0;
    }

    @AfterEach
    void removeSyntheticRows() {
        Object[] all = ids.toArray();
        jdbc.update("delete from app.movies where id = any(?)", ps -> ps.setArray(1,
                ps.getConnection().createArrayOf("int4", all)));
        jdbc.update("delete from app.catalog_seed_rejects where movie_id = any(?)", ps -> ps.setArray(1,
                ps.getConnection().createArrayOf("int4", all)));
        jdbc.update("delete from app.catalog_seed_state");
        clock.reset();
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
        public CatalogPage discoverByYear(int year, int minVoteCount, int page) {
            calls.add("y" + year + "p" + page);
            return fixture.discoverByYear(year, minVoteCount, page);
        }

        @Override
        public CatalogMovie details(int id) {
            calls.add("d" + id);
            return fixture.details(id);
        }
    };

    private CatalogSeedService seeder(int maxDatabaseMb, DatabaseSizeProbe probe) {
        CatalogSeedProperties properties = new CatalogSeedProperties(null, 0, null, 1000d, 0, Duration.ZERO, null, 1,
                null, null, null, maxDatabaseMb, null, new CatalogSeedProperties.Discover(YEAR, YEAR, 1, 0), null);
        TmdbIdExport export = new TmdbIdExport(uri -> Optional.of(new ByteArrayInputStream(exportFile)),
                properties.export());
        return new CatalogSeedService(recording, store, movies, state, rejects, export, probe, properties, clock,
                new RequestPacer(1000, System::nanoTime, duration -> { }), duration -> { });
    }

    private CatalogSeedService seeder() {
        return seeder(0, () -> 0L);
    }

    private static SeedPlan exportPlan() {
        return new SeedPlan(0, List.of(), true, true, false);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, (Object) ids.toArray(Integer[]::new));
    }

    private String today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
    }

    @Test
    void theExportSeedsOnlyMoviesThatPassTheBarAndRemembersTheRejects() {
        SeedReport report = seeder().seed(exportPlan());

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.candidates()).isEqualTo(EXPORTED);
        assertThat(report.fetched()).isEqualTo(20);
        assertThat(report.rejectedQuality()).isEqualTo(10);
        assertThat(report.notFound()).isEqualTo(EXPORTED - KNOWN);
        assertThat(count("select count(*) from app.movies where id = any(?) and vote_count >= 100 "
                + "and poster_path is not null and certification_checked_at is not null")).isEqualTo(20);
        assertThat(count("select count(*) from app.movies where id = any(?) and poster_path is null")).isZero();
        assertThat(count("select count(*) from app.catalog_seed_rejects where movie_id = any(?)")).isEqualTo(10);
        assertThat(state.cursor("export")).isEmpty();

        calls.clear();
        SeedReport again = seeder().seed(exportPlan());

        assertThat(again.skippedAlreadySeeded()).isEqualTo(20);
        assertThat(again.skippedRejected()).isEqualTo(10);
        assertThat(again.fetched()).isZero();
        assertThat(calls).doesNotContain("d" + ids.get(0), "d" + ids.get(1));
    }

    @Test
    void aRejectedMoviesExistingRowIsLeftAsItIs() {
        int id = ids.get(3);
        store.storeListResults(List.of(new CatalogMovie(id, "Listed earlier", YEAR, null, null, null, null,
                List.of(), 1e-9, 5.0)));

        seeder().seed(exportPlan());

        assertThat(jdbc.queryForObject("select title from app.movies where id = ?", String.class, id))
                .isEqualTo("Listed earlier");
        assertThat(jdbc.queryForObject("select certification_checked_at is null from app.movies where id = ?",
                Boolean.class, id)).isTrue();
        assertThat(rejects.rejectedSince(List.of(id), clock.instant().minus(Duration.ofMinutes(5)))).containsExactly(id);
    }

    @Test
    void theSizeGuardStopsAnExportBetweenChunksAndTheNextRunResumesAtItsCursor() {
        // The size is fine for the first two checks (before the download, before chunk 1), then too big.
        AtomicInteger checks = new AtomicInteger();
        SeedReport stopped = seeder(300, () -> checks.incrementAndGet() <= 2 ? 0L : Long.MAX_VALUE).seed(exportPlan());

        assertThat(stopped.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(stopped.candidates()).isEqualTo(CatalogSeedService.ID_CHUNK);
        assertThat(state.cursor("export")).contains(new Cursor(CatalogSeedService.ID_CHUNK + 1, today()));

        SeedReport resumed = seeder(300, () -> 0L).seed(exportPlan());

        assertThat(resumed.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(resumed.candidates()).isEqualTo(EXPORTED - CatalogSeedService.ID_CHUNK);
        assertThat(state.cursor("export")).isEmpty();
    }

    @Test
    void theRealSizeProbeReadsPgDatabaseSizeAndAnOversizedDatabaseStopsTheRun() {
        DatabaseSizeProbe probe = new DatabaseSizeProbe.Postgres(jdbc);
        assertThat(probe.currentBytes()).isGreaterThan(1024L * 1024);

        SeedReport report = seeder(1, probe).seed(exportPlan());

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(calls).isEmpty();
    }

    @Test
    void discoverRejectsObviousMissesFromTheListAndSeedsTheRest() {
        SeedReport report = seeder().seed(new SeedPlan(0, List.of(), true, false, true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.pages()).isEqualTo(1);
        // Page 1 = the 20 most-voted synthetic movies (indexes 29..10); 6 of them have no poster.
        assertThat(report.candidates()).isEqualTo(20);
        assertThat(report.rejectedQuality()).isEqualTo(6);
        assertThat(report.fetched()).isEqualTo(14);
        assertThat(calls).filteredOn(call -> call.startsWith("d")).hasSize(14);
        assertThat(state.cursor("discover")).isEmpty();
    }

    @Test
    void mlReaderCanReadVoteCountsButNotTheRejects() {
        assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.catalog_seed_rejects', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_column_privilege('ml_reader', 'app.movies', 'vote_count', 'SELECT')",
                Boolean.class)).isTrue();
    }
}
