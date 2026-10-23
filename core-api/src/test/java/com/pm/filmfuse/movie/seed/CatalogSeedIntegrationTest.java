package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.catalog.CatalogAuthenticationException;
import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.catalog.CatalogPage;
import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import com.pm.filmfuse.movie.repository.MovieEnrichmentRepository;
import com.pm.filmfuse.movie.seed.CatalogSeedService.SeedPlan;
import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import com.pm.filmfuse.movie.service.CatalogEnrichmentService;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeder against the real schema. It seeds a fixture catalog of its own - synthetic movies under
 * a genre no other test uses, with near-zero popularity, deleted afterwards - never the bundled
 * fixture-movies.json, so the shared test database does not fill up with well-known movies (see
 * {@code sparse-below} in CatalogEnrichmentProperties).
 */
class CatalogSeedIntegrationTest extends AbstractIntegrationTest {

    private static final int MOVIES = 25;

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
    /** popular(..) and details(..) calls the seeder made, as "p<page>" / "d<id>". */
    private final List<String> calls = new ArrayList<>();
    /** Ids whose details answer 401. */
    private Set<Integer> rejected = Set.of();

    @BeforeEach
    void syntheticCatalog() {
        jdbc.update("delete from app.catalog_seed_state");
        int genre = ThreadLocalRandom.current().nextInt(1_000_000, 900_000_000);
        int base = ThreadLocalRandom.current().nextInt(1_000_000_000, Integer.MAX_VALUE - 1_000);
        ids = IntStream.range(0, MOVIES).map(i -> base + i).boxed().toList();
        // Popularity falls with the index, so page 1 = the first 20 ids, page 2 = the last 5.
        fixture = new FixtureMovieCatalogClient(IntStream.range(0, MOVIES).mapToObj(i -> new CatalogMovie(
                ids.get(i), "Seeded " + i, 2000 + i, 90 + i, null, null, "Overview " + i, List.of(genre),
                (MOVIES - i) * 1e-9, 6.5, i % 2 == 0 ? "PG-13" : "R", List.of("murder", "violence", "profanity")))
                .toList());
    }

    @AfterEach
    void removeSyntheticMovies() {
        jdbc.update("delete from app.movies where id = any(?)", ps -> ps.setArray(1,
                ps.getConnection().createArrayOf("int4", ids.toArray())));
        jdbc.update("delete from app.catalog_seed_state");
    }

    private final MovieCatalogClient recording = new MovieCatalogClient() {
        @Override
        public CatalogPage search(String query, int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CatalogPage popular(Integer genreId, int page) {
            calls.add("p" + page);
            return fixture.popular(genreId, page);
        }

        @Override
        public CatalogMovie details(int id) {
            calls.add("d" + id);
            if (rejected.contains(id)) {
                throw new CatalogAuthenticationException("Movie data is temporarily unavailable.", null);
            }
            return fixture.details(id);
        }
    };

    private CatalogSeedService seeder() {
        CatalogSeedProperties properties = new CatalogSeedProperties(null, null, null, 1000d, 0, Duration.ZERO, null,
                1, null, null);
        return new CatalogSeedService(recording, store, movies, state, rejects, null, () -> 0L, properties, clock,
                new RequestPacer(1000, System::nanoTime, duration -> { }), duration -> { });
    }

    private SeedReport seed() {
        return seeder().seed(new SeedPlan(500, List.of(), true));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, (Object) ids.toArray(Integer[]::new));
    }

    @Test
    void seedsMoviesWithCertificationKeywordsAndContentLevels() {
        SeedReport report = seed();

        assertThat(report.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(report.pages()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(MOVIES);
        assertThat(report.fetched()).isEqualTo(MOVIES);
        assertThat(count("select count(*) from app.movies where id = any(?) and certification in ('PG-13', 'R') "
                + "and certification_checked_at is not null and runtime is not null and cardinality(keywords) = 3"))
                .isEqualTo(MOVIES);
        assertThat(count("select count(distinct movie_id) from app.movie_content_levels where movie_id = any(?) "
                + "and source = 'keywords' and dimension = 'violence' and level <> 'none'")).isEqualTo(MOVIES);
        assertThat(jdbc.queryForObject("select certification from app.movies where id = ?", String.class, ids.get(1)))
                .isEqualTo("R");
        assertThat(state.nextPage("popular")).isEmpty();
    }

    @Test
    void aSecondRunSkipsEverythingAlreadySeeded() {
        seed();
        calls.clear();

        SeedReport again = seed();

        assertThat(again.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(again.fetched()).isZero();
        assertThat(again.skippedAlreadySeeded()).isEqualTo(MOVIES);
        assertThat(calls).containsExactly("p1", "p2");
    }

    @Test
    void moviesAreRefetchedOnceRefreshAfterHasPassed() {
        seed();
        clock.advance(Duration.ofDays(8));
        try {
            assertThat(seed().fetched()).isEqualTo(MOVIES);
        } finally {
            clock.reset();
        }
    }

    @Test
    void aStoppedRunResumesAtThePageItStoppedOn() {
        // Page 2's first movie gets a 401: with auth-failure-limit 1 the run stops there.
        rejected = Set.of(ids.get(20));

        SeedReport first = seed();

        assertThat(first.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(first.fetched()).isEqualTo(20);
        assertThat(state.nextPage("popular")).contains(2);
        assertThat(count("select count(*) from app.movies where id = any(?) and certification_checked_at is null"))
                .as("page 2's list results are cached, waiting for details").isEqualTo(5);

        rejected = Set.of();
        calls.clear();
        SeedReport resumed = seed();

        assertThat(resumed.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(calls).first().isEqualTo("p2");
        assertThat(calls).doesNotContain("p1");
        assertThat(resumed.fetched()).isEqualTo(5);
        assertThat(state.nextPage("popular")).isEmpty();
        assertThat(count("select count(*) from app.movies where id = any(?) and certification_checked_at is not null"))
                .isEqualTo(MOVIES);
    }

    @Test
    void idsFromAFileAreSeededAndUnknownOnesCounted() {
        SeedReport report = seeder().seed(new SeedPlan(0, List.of(ids.get(3), ids.get(4), 7), false));

        assertThat(report.fetched()).isEqualTo(2);
        assertThat(report.notFound()).isEqualTo(1);
        assertThat(count("select count(*) from app.movies where id = any(?)")).isEqualTo(2);
        assertThat(state.nextPage("popular")).isEmpty();
    }

    @Test
    void mlReaderCannotReadTheSeedState() {
        assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.catalog_seed_state', 'SELECT')",
                Boolean.class)).isFalse();
    }
}
