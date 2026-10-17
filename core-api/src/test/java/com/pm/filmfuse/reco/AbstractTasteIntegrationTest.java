package com.pm.filmfuse.reco;

import com.pm.filmfuse.group.AbstractGroupIntegrationTest;
import com.pm.filmfuse.reco.service.VectorBuildExecutorConfig;
import com.pm.filmfuse.reco.service.VectorBuildService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.pm.filmfuse.reco.service.VectorRefreshScheduler;
import java.sql.Array;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the Phase 1b taste tests (onboarding, ratings, vector pipeline, For you, calibration).
 * Extends the group base so the whole suite keeps sharing one Spring context: same test clock,
 * same mocked (unconfigured by default) {@code MlServiceClient}.
 *
 * <p>The database is shared and never wiped, and For you / calibration rank the whole movie cache
 * by popularity, so movies seeded here get a popularity above every other test's and above the
 * previous call's ({@link #topMovie}).
 */
public abstract class AbstractTasteIntegrationTest extends AbstractGroupIntegrationTest {

    /** Increasing: a movie seeded later is always more popular than every earlier one. */
    private static final AtomicLong POPULARITY = new AtomicLong(1_000_000_000_000L + System.currentTimeMillis());

    /** Debounce under the default properties plus a margin. */
    protected static final Duration PAST_DEBOUNCE = Duration.ofSeconds(11);

    @Autowired
    protected VectorRefreshScheduler scheduler;

    @Autowired
    protected VectorBuildService vectorBuilder;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier(VectorBuildExecutorConfig.EXECUTOR)
    protected ThreadPoolTaskExecutor vectorBuildExecutor;

    /** Waits (up to 10 s) until the user's vector reaches {@code expected}. */
    protected void awaitVersion(TestUser user, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (version(user) < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        org.assertj.core.api.Assertions.assertThat(version(user)).isEqualTo(expected);
    }

    /** Waits (up to 10 s) until no background vector build is running or queued. */
    protected void awaitBuildsIdle() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((vectorBuildExecutor.getActiveCount() > 0
                || !vectorBuildExecutor.getThreadPoolExecutor().getQueue().isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** A cached movie more popular than anything seeded before it. */
    protected int topMovie(int genre, String certification) {
        return movie(genre, certification, POPULARITY.incrementAndGet());
    }

    protected int topMovie(int genre, String certification, Map<String, String> levels) {
        return movie(genre, certification, POPULARITY.incrementAndGet(), levels);
    }

    // ------------------------------------------------------------------------------- API helpers

    protected ResultActions putJson(TestUser user, String path, String json) throws Exception {
        return mockMvc.perform(put(path).with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions postJson(TestUser user, String path, String json) throws Exception {
        return mockMvc.perform(post(path).with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions rate(TestUser user, int movieId, String json) throws Exception {
        return putJson(user, "/api/v1/ratings/" + movieId, json);
    }

    protected void rate(TestUser user, int movieId, int score) throws Exception {
        rate(user, movieId, "{\"score\": " + score + "}").andExpect(status().isOk());
    }

    protected void setGenres(TestUser user, String json) throws Exception {
        putJson(user, "/api/v1/me/preferences/genres", json).andExpect(status().isOk());
    }

    protected void setMoods(TestUser user, String json) throws Exception {
        putJson(user, "/api/v1/me/preferences/moods", json).andExpect(status().isOk());
    }

    protected void setFavorites(TestUser user, int... movieIds) throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < movieIds.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"movieId\": ").append(movieIds[i]).append(", \"rank\": ")
                    .append(i + 1).append("}");
        }
        putJson(user, "/api/v1/me/preferences/favorites", json.append("]").toString()).andExpect(status().isOk());
    }

    /** Genre prefs that satisfy onboarding completion: 5 genres, 3 of them 8+. */
    protected static final String QUALIFYING_GENRES = """
            [{"genreId": 28, "score": 9}, {"genreId": 878, "score": 8}, {"genreId": 10749, "score": 8},
             {"genreId": 27, "score": 2}, {"genreId": 35, "score": 6}]
            """;

    /** Prefs (qualifying genres + 3 favourites) - everything completion needs except the username. */
    protected void qualifyForCompletion(TestUser user) throws Exception {
        setGenres(user, QUALIFYING_GENRES);
        int genre = uniqueGenre();
        setFavorites(user, movie(genre, "PG", 1), movie(genre, "PG", 1), movie(genre, "PG", 1));
    }

    /** Builds the user's vector now (whatever the debounce); fails the test when nothing was dirty. */
    protected int buildVector(TestUser user) {
        return vectorBuilder.rebuildIfDirty(user.id()).orElseThrow(() -> new AssertionError("vector was not dirty"));
    }

    /** Runs the scheduler pass until nothing more is due (the shared DB may hold other tests' users). */
    protected void drainRefresh() {
        for (int i = 0; i < 100 && scheduler.refreshDue() > 0; i++) {
            // keep going
        }
    }

    // ------------------------------------------------------------------------------- DB reads

    protected Map<String, Object> vectorRow(TestUser user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from app.user_interest_vectors where user_id = ?", user.id());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected boolean isDirty(TestUser user) {
        Map<String, Object> row = vectorRow(user);
        return row != null && Boolean.TRUE.equals(row.get("dirty"));
    }

    protected String dirtyReason(TestUser user) {
        Map<String, Object> row = vectorRow(user);
        return row == null ? null : (String) row.get("dirty_reason");
    }

    protected int version(TestUser user) {
        Map<String, Object> row = vectorRow(user);
        return row == null ? 0 : ((Number) row.get("version")).intValue();
    }

    protected List<Double> interpretable(TestUser user) throws Exception {
        Map<String, Object> row = vectorRow(user);
        Object array = row == null ? null : row.get("interpretable");
        if (array == null) {
            return null;
        }
        return Arrays.stream((Object[]) ((Array) array).getArray()).map(v -> ((Number) v).doubleValue()).toList();
    }

    protected int count(String table, UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from app." + table + " where user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
