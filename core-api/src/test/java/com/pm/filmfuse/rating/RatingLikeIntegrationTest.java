package com.pm.filmfuse.rating;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST/DELETE /ratings/{id}/like, likeCount / likedByMe / mine, and rating_likes' lifecycle. */
class RatingLikeIntegrationTest extends AbstractFeedIntegrationTest {

    @Test
    void likeAndUnlikeAreIdempotent() throws Exception {
        TestUser author = user();
        TestUser fan = user();
        String id = review(author, newMovie(), "PUBLIC");

        like(fan, id).andExpect(status().isNoContent());
        like(fan, id).andExpect(status().isNoContent());
        assertThat(likeRows(id)).isEqualTo(1);

        unlike(fan, id).andExpect(status().isNoContent());
        unlike(fan, id).andExpect(status().isNoContent());
        assertThat(likeRows(id)).isZero();
    }

    @Test
    void unlikeIs204EvenForAnUnknownOrInvisibleRating() throws Exception {
        TestUser author = user();
        TestUser stranger = user();
        String hidden = review(author, newMovie(), "FRIENDS");
        unlike(stranger, hidden).andExpect(status().isNoContent());
        unlike(stranger, java.util.UUID.randomUUID().toString()).andExpect(status().isNoContent());
    }

    @Test
    void aRepeatedLikeKeepsTheFirstTimestamp() throws Exception {
        TestUser author = user();
        TestUser fan = user();
        String id = review(author, newMovie(), "PUBLIC");
        like(fan, id).andExpect(status().isNoContent());
        Object first = jdbc.queryForObject("select created_at from app.rating_likes where rating_id = ?::uuid",
                Object.class, id);
        CLOCK.advance(java.time.Duration.ofMinutes(5));
        like(fan, id).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select created_at from app.rating_likes where rating_id = ?::uuid",
                Object.class, id)).isEqualTo(first);
    }

    @Test
    void likeCountLikedByMeAndMineArePerViewer() throws Exception {
        TestUser author = user();
        TestUser fanA = user();
        TestUser fanB = user();
        TestUser quiet = user();
        for (TestUser friend : new TestUser[] {fanA, fanB, quiet}) {
            befriend(author, friend);
        }
        String liked = review(author, newMovie(), "FRIENDS");
        String unliked = review(author, newMovie(), "FRIENDS");
        like(fanA, liked).andExpect(status().isNoContent());
        like(fanB, liked).andExpect(status().isNoContent());
        like(author, liked).andExpect(status().isNoContent());

        assertItem(fanA, liked, 3, true, false);
        assertItem(fanB, liked, 3, true, false);
        assertItem(quiet, liked, 3, false, false);
        assertItem(author, liked, 3, true, true);
        assertItem(fanA, unliked, 0, false, false);
        assertItem(author, unliked, 0, false, true);

        unlike(fanA, liked).andExpect(status().isNoContent());
        assertItem(fanA, liked, 2, false, false);
        assertItem(fanB, liked, 2, true, false);

        // The same values on the other two lists.
        userRatings(fanB, author.id(), null)
                .andExpect(jsonPath("$.items[1].id").value(liked))
                .andExpect(jsonPath("$.items[1].likeCount").value(2))
                .andExpect(jsonPath("$.items[1].likedByMe").value(true))
                .andExpect(jsonPath("$.items[1].mine").value(false));
        int movie = jdbc.queryForObject("select movie_id from app.ratings where id = ?::uuid", Integer.class, liked);
        movieRatings(author, movie, FRIENDS, null)
                .andExpect(jsonPath("$.items[0].likeCount").value(2))
                .andExpect(jsonPath("$.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.items[0].mine").value(true));
    }

    @Test
    void likesFromPeopleWhoCanNoLongerSeeTheRatingStillCount() throws Exception {
        TestUser author = user();
        TestUser former = user();
        TestUser friend = user();
        befriend(author, former);
        befriend(author, friend);
        String id = review(author, newMovie(), "FRIENDS");
        like(former, id).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/friends/{id}", author.id()).with(former.auth()))
                .andExpect(status().isNoContent());

        assertItem(friend, id, 1, false, false);
        // ...and the former friend can still take their like back.
        unlike(former, id).andExpect(status().isNoContent());
        assertItem(friend, id, 0, false, false);
    }

    @Test
    void deletingARatingDeletesItsLikes() throws Exception {
        TestUser author = user();
        TestUser fan = user();
        int movie = newMovie();
        String id = review(author, movie, "PUBLIC");
        like(fan, id).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/ratings/{movieId}", movie).with(author.auth()))
                .andExpect(status().isNoContent());

        assertThat(likeRows(id)).isZero();
        like(fan, id).andExpect(status().isNotFound());

        // Rating the movie again is a new rating with no likes.
        String fresh = review(author, movie, "PUBLIC");
        assertThat(fresh).isNotEqualTo(id);
        movieRatings(fan, movie, EVERYONE, null).andExpect(jsonPath("$.items[0].likeCount").value(0));
    }

    @Test
    void accountDeletionCascadesLikesBothWays() throws Exception {
        TestUser author = user();
        TestUser leavingFan = user();
        TestUser stayingFan = user();
        String kept = review(author, newMovie(), "PUBLIC");
        like(leavingFan, kept).andExpect(status().isNoContent());
        like(stayingFan, kept).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/me").with(leavingFan.auth())).andExpect(status().isNoContent());
        assertThat(likeRows(kept)).isEqualTo(1);
        assertThat(count("rating_likes", leavingFan.id())).isZero();

        TestUser leavingAuthor = user();
        String gone = review(leavingAuthor, newMovie(), "PUBLIC");
        like(stayingFan, gone).andExpect(status().isNoContent());
        like(leavingAuthor, kept).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/me").with(leavingAuthor.auth())).andExpect(status().isNoContent());
        assertThat(likeRows(gone)).isZero();
        assertThat(likeRows(kept)).isEqualTo(1);
        assertThat(count("rating_likes", stayingFan.id())).isEqualTo(1);
        like(stayingFan, gone).andExpect(status().isNotFound());
    }

    @Test
    void mlReaderCanReadButNotWriteRatingLikes() {
        assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.rating_likes', 'SELECT')",
                Boolean.class)).isTrue();
        for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE"}) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.rating_likes', ?)",
                    Boolean.class, privilege)).as(privilege).isFalse();
        }
    }

    @Test
    void theFeedIndexesExist() {
        for (String index : new String[] {"rating_likes_pkey", "rating_likes_user_idx", "ratings_user_updated_idx",
                "ratings_public_updated_idx", "ratings_movie_updated_idx"}) {
            assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = 'app' and indexname = ?",
                    Integer.class, index)).as(index).isEqualTo(1);
        }
    }

    private void assertItem(TestUser viewer, String ratingId, int likeCount, boolean likedByMe, boolean mine)
            throws Exception {
        Map<String, Object> item = friendsFeedItem(viewer, ratingId);
        assertThat(item.get("likeCount")).as("likeCount").isEqualTo(likeCount);
        assertThat(item.get("likedByMe")).as("likedByMe").isEqualTo(likedByMe);
        assertThat(item.get("mine")).as("mine").isEqualTo(mine);
    }
}
