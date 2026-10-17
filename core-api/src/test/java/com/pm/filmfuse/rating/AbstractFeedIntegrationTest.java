package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for the ratings-feed tests. Extends the taste base so the whole suite shares one Spring
 * context.
 *
 * <p>The database is shared and never wiped: a FRIENDS feed or a movie's ratings only ever contain
 * the test's own users and movies, but the EVERYONE feed holds every test's public ratings, so
 * EVERYONE assertions walk every page ({@link #everyone}) and look for the test's own ids.
 */
abstract class AbstractFeedIntegrationTest extends AbstractTasteIntegrationTest {

    protected static final String FRIENDS = "FRIENDS";
    protected static final String EVERYONE = "EVERYONE";

    /** A cached movie of the test's own. */
    protected int newMovie() {
        return movie(uniqueGenre(), "PG-13", 1);
    }

    /**
     * {@code author} rates {@code movieId} with a note and {@code visibility}, one second after the
     * previous write (so feed order is deterministic); returns the rating id.
     */
    protected String review(TestUser author, int movieId, String visibility) throws Exception {
        return review(author, movieId, visibility, 8);
    }

    protected String review(TestUser author, int movieId, String visibility, int score) throws Exception {
        CLOCK.advance(Duration.ofSeconds(1));
        String body = json(rate(author, movieId, """
                {"score": %d, "body": "Thoughts on %d", "visibility": "%s"}""".formatted(score, movieId, visibility))
                .andExpect(status().isOk()));
        return JsonPath.read(body, "$.id");
    }

    /** A calibration swipe ({@code ONBOARDING_SWIPE}, PRIVATE); returns its rating id. */
    protected String swipe(TestUser user, int movieId, int score) throws Exception {
        postJson(user, "/api/v1/me/ratings/batch", "[{\"movieId\": " + movieId + ", \"score\": " + score + "}]")
                .andExpect(status().isNoContent());
        return ratingId(user, movieId);
    }

    protected String ratingId(TestUser user, int movieId) {
        return jdbc.queryForObject("select id::text from app.ratings where user_id = ? and movie_id = ?",
                String.class, user.id(), movieId);
    }

    // ------------------------------------------------------------------------------- requests

    protected ResultActions feed(TestUser viewer, String scope, String cursor) throws Exception {
        return mockMvc.perform(withCursor(get("/api/v1/feed").param("scope", scope), cursor).with(viewer.auth()));
    }

    protected ResultActions movieRatings(TestUser viewer, int movieId, String scope, String cursor) throws Exception {
        return mockMvc.perform(withCursor(get("/api/v1/movies/{id}/ratings", movieId).param("scope", scope), cursor)
                .with(viewer.auth()));
    }

    protected ResultActions userRatings(TestUser viewer, UUID userId, String cursor) throws Exception {
        return mockMvc.perform(withCursor(get("/api/v1/users/{id}/ratings", userId), cursor).with(viewer.auth()));
    }

    protected ResultActions like(TestUser viewer, String ratingId) throws Exception {
        return mockMvc.perform(post("/api/v1/ratings/{id}/like", ratingId).with(viewer.auth()));
    }

    protected ResultActions unlike(TestUser viewer, String ratingId) throws Exception {
        return mockMvc.perform(delete("/api/v1/ratings/{id}/like", ratingId).with(viewer.auth()));
    }

    private static MockHttpServletRequestBuilder withCursor(MockHttpServletRequestBuilder request, String cursor) {
        return cursor == null ? request : request.param("cursor", cursor);
    }

    // ------------------------------------------------------------------------------- whole lists

    /** Every item of a paged list, in order, following nextCursor to the end. */
    protected interface PageFetcher {
        ResultActions fetch(String cursor) throws Exception;
    }

    protected static List<Map<String, Object>> all(PageFetcher fetcher) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = null;
        for (int pages = 0; pages < 10_000; pages++) {
            String body = json(fetcher.fetch(cursor).andExpect(status().isOk()));
            List<Map<String, Object>> page = JsonPath.read(body, "$.items");
            items.addAll(page);
            cursor = JsonPath.read(body, "$.nextCursor");
            if (cursor == null) {
                return items;
            }
        }
        throw new AssertionError("Too many pages");
    }

    protected static List<String> ids(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("id")).toList();
    }

    protected List<String> friendsFeed(TestUser viewer) throws Exception {
        return ids(all(cursor -> feed(viewer, FRIENDS, cursor)));
    }

    protected List<String> everyone(TestUser viewer) throws Exception {
        return ids(all(cursor -> feed(viewer, EVERYONE, cursor)));
    }

    protected List<String> onMovie(TestUser viewer, int movieId, String scope) throws Exception {
        return ids(all(cursor -> movieRatings(viewer, movieId, scope, cursor)));
    }

    protected List<String> byUser(TestUser viewer, TestUser author) throws Exception {
        return ids(all(cursor -> userRatings(viewer, author.id(), cursor)));
    }

    /** The item with {@code ratingId} in {@code viewer}'s FRIENDS feed. */
    protected Map<String, Object> friendsFeedItem(TestUser viewer, String ratingId) throws Exception {
        return all(cursor -> feed(viewer, FRIENDS, cursor)).stream()
                .filter(item -> ratingId.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ratingId + " is not in the feed"));
    }

    protected int likeRows(String ratingId) {
        Integer n = jdbc.queryForObject("select count(*) from app.rating_likes where rating_id = ?::uuid",
                Integer.class, ratingId);
        return n == null ? 0 : n;
    }
}
