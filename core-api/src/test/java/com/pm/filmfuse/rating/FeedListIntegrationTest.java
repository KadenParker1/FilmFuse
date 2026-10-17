package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.common.pagination.CursorCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The item shape, keyset ordering and paging of the three feed lists, and their 400s / 401s. */
class FeedListIntegrationTest extends AbstractFeedIntegrationTest {

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/feed").param("scope", FRIENDS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/movies/1/ratings").param("scope", FRIENDS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/{id}/ratings", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/ratings/{id}/like", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/ratings/{id}/like", id)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------- item shape

    @Test
    void anItemCarriesAuthorMovieCertificationAndRatingFields() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int movie = movie(uniqueGenre(), "PG-13", 1);
        rate(author, movie, """
                {"score": 9, "body": "Loved it", "containsSpoilers": true, "visibility": "FRIENDS"}""")
                .andExpect(status().isOk());

        feed(friend, FRIENDS, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.items[0].id").value(ratingId(author, movie)))
                .andExpect(jsonPath("$.items[0].author.id").value(author.id().toString()))
                .andExpect(jsonPath("$.items[0].author.username").value(author.username()))
                .andExpect(jsonPath("$.items[0].author.friendship").value("FRIENDS"))
                .andExpect(jsonPath("$.items[0].movie.id").value(movie))
                .andExpect(jsonPath("$.items[0].movie.title").value("Movie " + movie))
                .andExpect(jsonPath("$.items[0].certification").value("PG_13"))
                .andExpect(jsonPath("$.items[0].score").value(9))
                .andExpect(jsonPath("$.items[0].body").value("Loved it"))
                .andExpect(jsonPath("$.items[0].containsSpoilers").value(true))
                .andExpect(jsonPath("$.items[0].visibility").value("FRIENDS"))
                .andExpect(jsonPath("$.items[0].likeCount").value(0))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false))
                .andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty());

        feed(author, FRIENDS, null)
                .andExpect(jsonPath("$.items[0].mine").value(true))
                .andExpect(jsonPath("$.items[0].author.friendship").value("NONE"));
    }

    @Test
    void aScoreOnlyRatingAndAnUncertifiedMovieComeBackWithNulls() throws Exception {
        TestUser author = user();
        int movie = movie(uniqueGenre(), null, 1);
        rate(author, movie, "{\"score\": 4, \"visibility\": \"PUBLIC\"}").andExpect(status().isOk());

        movieRatings(user(), movie, EVERYONE, null)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].body").value(nullValue()))
                .andExpect(jsonPath("$.items[0].certification").value(nullValue()))
                .andExpect(jsonPath("$.items[0].author.friendship").value("NONE"))
                .andExpect(jsonPath("$.items[0].score").value(4));
    }

    // ------------------------------------------------------------------------------- order / paging

    @Test
    void reRatingMovesTheItemToTheTopAndKeepsItsIdAndLikes() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int first = newMovie();
        String older = review(author, first, "FRIENDS");
        String newer = review(author, newMovie(), "FRIENDS");
        like(friend, older).andExpect(status().isNoContent());
        assertThat(friendsFeed(friend)).containsExactly(newer, older);

        String again = review(author, first, "PUBLIC", 3);

        assertThat(again).isEqualTo(older);
        assertThat(friendsFeed(friend)).containsExactly(older, newer);
        Map<String, Object> item = friendsFeedItem(friend, older);
        assertThat(item.get("likeCount")).isEqualTo(1);
        assertThat(item.get("likedByMe")).isEqualTo(true);
        assertThat(item.get("score")).isEqualTo(3);
        assertThat(item.get("visibility")).isEqualTo("PUBLIC");
        assertThat((String) item.get("updatedAt")).isNotEqualTo(item.get("createdAt"));
    }

    @Test
    void pagesAre20AndKeysetOrderBreaksTimestampTiesById() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        List<Integer> movies = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            int movie = newMovie();
            movies.add(movie);
            rate(author, movie, "{\"score\": 5, \"visibility\": \"" + (i % 2 == 0 ? "PUBLIC" : "FRIENDS") + "\"}")
                    .andExpect(status().isOk());
        }
        // Three groups of 15 with identical updated_at: only the id orders inside a group.
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < movies.size(); i++) {
            jdbc.update("update app.ratings set updated_at = ? where user_id = ? and movie_id = ?",
                    Timestamp.from(base.plusSeconds(i / 15)), author.id(), movies.get(i));
        }
        List<Map<String, Object>> expected = jdbc.queryForList(
                "select id::text as id from app.ratings where user_id = ? order by updated_at desc, id desc",
                author.id());
        List<String> expectedIds = expected.stream().map(row -> (String) row.get("id")).toList();

        String page1 = json(feed(friend, FRIENDS, null).andExpect(jsonPath("$.items", hasSize(20))));
        String page2 = json(feed(friend, FRIENDS, JsonPath.read(page1, "$.nextCursor"))
                .andExpect(jsonPath("$.items", hasSize(20))));
        String page3 = json(feed(friend, FRIENDS, JsonPath.read(page2, "$.nextCursor"))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue())));
        List<String> paged = new ArrayList<>();
        for (String page : List.of(page1, page2, page3)) {
            List<String> ids = JsonPath.read(page, "$.items[*].id");
            paged.addAll(ids);
        }
        assertThat(paged).containsExactlyElementsOf(expectedIds);

        // The same order through the other two lists.
        assertThat(byUser(friend, author)).containsExactlyElementsOf(expectedIds);
        assertThat(byUser(author, author)).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void pagingIsStableWhileNewRatingsArrive() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            before.addFirst(review(author, newMovie(), "FRIENDS"));
        }
        String page1 = json(feed(friend, FRIENDS, null));
        List<String> firstIds = JsonPath.read(page1, "$.items[*].id");

        String arrived = review(author, newMovie(), "FRIENDS");
        String page2 = json(feed(friend, FRIENDS, JsonPath.read(page1, "$.nextCursor")));
        List<String> secondIds = JsonPath.read(page2, "$.items[*].id");

        List<String> all = new ArrayList<>(firstIds);
        all.addAll(secondIds);
        assertThat(all).containsExactlyElementsOf(before).doesNotContain(arrived);
        assertThat(new HashSet<>(all)).hasSize(25);
        assertThat(friendsFeed(friend).getFirst()).isEqualTo(arrived);
    }

    @Test
    void theEveryoneFeedIsNewestFirstAcrossAuthors() throws Exception {
        TestUser a = user();
        TestUser b = user();
        String one = review(a, newMovie(), "PUBLIC");
        String two = review(b, newMovie(), "PUBLIC");
        String three = review(a, newMovie(), "PUBLIC");

        List<String> everyone = everyone(user());
        assertThat(everyone).containsSubsequence(three, two, one);
        List<Map<String, Object>> items = all(cursor -> feed(a, EVERYONE, cursor));
        assertThat(items).isSortedAccordingTo(Comparator
                .comparing((Map<String, Object> item) -> Instant.parse((String) item.get("updatedAt"))).reversed()
                .thenComparing(item -> UUID.fromString((String) item.get("id")), (x, y) -> -compareUuids(x, y)));
    }

    @Test
    void aMoviesRatingsAreScopedToThatMovie() throws Exception {
        TestUser me = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(me, friend);
        int movie = newMovie();
        int other = newMovie();
        String friendsOnMovie = review(friend, movie, "FRIENDS");
        String strangerOnMovie = review(stranger, movie, "PUBLIC");
        String mineOnMovie = review(me, movie, "FRIENDS");
        review(friend, other, "PUBLIC");
        review(stranger, other, "PUBLIC");

        assertThat(onMovie(me, movie, FRIENDS)).containsExactly(mineOnMovie, friendsOnMovie);
        assertThat(onMovie(me, movie, EVERYONE)).containsExactly(strangerOnMovie);
    }

    // ------------------------------------------------------------------------------- 400s

    @Test
    void scopeIsRequiredAndMustBeAKnownValue() throws Exception {
        TestUser me = user();
        int movie = newMovie();
        mockMvc.perform(get("/api/v1/feed").with(me.auth())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(get("/api/v1/movies/{id}/ratings", movie).with(me.auth())).andExpect(status().isBadRequest());
        for (String bad : new String[] {"ALL", "friends", ""}) {
            feed(me, bad, null).andExpect(status().isBadRequest());
            movieRatings(me, movie, bad, null).andExpect(status().isBadRequest());
        }
    }

    @Test
    void aBadCursorIs400OnEveryList() throws Exception {
        TestUser me = user();
        int movie = newMovie();
        String[] bad = {
                "not base64 !",
                CursorCodec.encode(List.of("only-one-part")),
                CursorCodec.encode(List.of("yesterday", UUID.randomUUID().toString())),
                CursorCodec.encode(List.of(Instant.now().toString(), "not-a-uuid")),
                CursorCodec.encode(List.of(Instant.now().toString(), UUID.randomUUID().toString(), "extra"))};
        for (String cursor : bad) {
            feed(me, FRIENDS, cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(CursorCodec.INVALID_CURSOR));
            feed(me, EVERYONE, cursor).andExpect(status().isBadRequest());
            movieRatings(me, movie, FRIENDS, cursor).andExpect(status().isBadRequest());
            userRatings(me, me.id(), cursor).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/users/not-a-uuid/ratings").with(me.auth())).andExpect(status().isBadRequest());
        like(me, "not-a-uuid").andExpect(status().isBadRequest());
    }

    /** Postgres uuid order (unsigned bytewise), which is what {@code id desc} uses. */
    private static int compareUuids(UUID a, UUID b) {
        int first = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return first != 0 ? first : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
