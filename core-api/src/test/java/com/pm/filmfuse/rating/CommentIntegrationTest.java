package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Comments on ratings (contract section C): visibility, blocks, validation, paging, delete, commentCount. */
class CommentIntegrationTest extends AbstractPhase5IntegrationTest {

    private List<String> commentIds(TestUser viewer, String ratingId) throws Exception {
        return ids(all(cursor -> comments(viewer, ratingId, cursor)));
    }

    private long commentCount(TestUser viewer, String ratingId) throws Exception {
        return ((Number) JsonPath.read(json(ratingDetail(viewer, ratingId).andExpect(status().isOk())),
                "$.commentCount")).longValue();
    }

    // ------------------------------------------------------------------------------- create

    @Test
    void createReturnsTheCommentForTheCaller() throws Exception {
        TestUser author = user();
        TestUser friend = user("fr" + uniquePrefix(), "Fran");
        befriend(author, friend);
        String ratingId = review(author, newMovie(), "FRIENDS");

        comment(friend, ratingId, "  Loved it too!  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.ratingId").value(ratingId))
                .andExpect(jsonPath("$.author.id").value(friend.id().toString()))
                .andExpect(jsonPath("$.author.username").value(friend.username()))
                .andExpect(jsonPath("$.author.displayName").value("Fran"))
                .andExpect(jsonPath("$.author.friendship").value("NONE"))
                .andExpect(jsonPath("$.body").value("Loved it too!"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.canDelete").value(true));
        assertThat(rows("select count(*) from app.rating_comments where rating_id = ?::uuid", ratingId)).isEqualTo(1);
    }

    @Test
    void bodyIsTrimmedAndMustBeOneTo500Characters() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        String max = "x".repeat(500);

        comment(author, ratingId, "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").value("A comment cannot be empty"));
        comment(author, ratingId, "").andExpect(status().isBadRequest());
        comment(author, ratingId, null).andExpect(status().isBadRequest());
        comment(author, ratingId, max + "y").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").value("A comment cannot exceed 500 characters"));
        comment(author, ratingId, "  " + max + "  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value(max));
        mockMvc.perform(post("/api/v1/ratings/{id}/comments", ratingId).with(author.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{nope")).andExpect(status().isBadRequest());
        comment(author, "not-a-uuid", "hi").andExpect(status().isBadRequest());
        assertThat(rows("select count(*) from app.rating_comments where rating_id = ?::uuid", ratingId)).isEqualTo(1);
    }

    @Test
    void onlyPeopleWhoCanSeeTheRatingCanCommentOrRead() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(author, friend);
        String friendsOnly = review(author, newMovie(), "FRIENDS");
        String priv = review(author, newMovie(), "PRIVATE");
        String pub = review(author, newMovie(), "PUBLIC");
        String swipe = swipe(author, newMovie(), 7);
        String unknown = java.util.UUID.randomUUID().toString();

        comment(stranger, friendsOnly, "hi").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Rating not found."));
        comments(stranger, friendsOnly, null).andExpect(status().isNotFound());
        comment(friend, friendsOnly, "hi").andExpect(status().isCreated());
        comment(stranger, pub, "hi").andExpect(status().isCreated());
        for (String hidden : List.of(priv, swipe, unknown)) {
            comment(friend, hidden, "hi").andExpect(status().isNotFound());
            comments(friend, hidden, null).andExpect(status().isNotFound());
        }
        // The author may note things on their own private rating; nobody else ever sees it.
        comment(author, priv, "note to self").andExpect(status().isCreated());
        comments(author, priv, null).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
        assertThat(rows("select count(*) from app.rating_comments where rating_id in (?::uuid, ?::uuid)", swipe,
                unknown)).isZero();
    }

    @Test
    void blocksEitherWayHideTheRatingFromCommenting() throws Exception {
        TestUser author = user();
        TestUser blocker = user();
        TestUser blocked = user();
        String pub = review(author, newMovie(), "PUBLIC");
        block(author, blocked);
        block(blocker, author);

        comment(blocked, pub, "hi").andExpect(status().isNotFound());
        comment(blocker, pub, "hi").andExpect(status().isNotFound());
        comments(blocked, pub, null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------- list

    @Test
    void listIsOldestFirstThirtyPerPage() throws Exception {
        TestUser author = user();
        TestUser other = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        List<String> posted = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            CLOCK.advance(java.time.Duration.ofMillis(10));
            posted.add(commentId(i % 2 == 0 ? author : other, ratingId, "c" + i));
        }

        String first = json(comments(other, ratingId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(30)))
                .andExpect(jsonPath("$.items[0].body").value("c0"))
                .andExpect(jsonPath("$.nextCursor").isString()));
        String cursor = JsonPath.read(first, "$.nextCursor");
        comments(other, ratingId, cursor).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[4].body").value("c34"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        assertThat(commentIds(other, ratingId)).containsExactlyElementsOf(posted);
        comments(other, ratingId, "garbage").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
    }

    @Test
    void sameTimestampCommentsPageStablyById() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        List<String> posted = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            posted.add(commentId(author, ratingId, "c" + i));
        }
        jdbc.update("update app.rating_comments set created_at = '2026-01-01T00:00:00Z' where rating_id = ?::uuid",
                ratingId);
        List<String> listed = commentIds(author, ratingId);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(posted).doesNotHaveDuplicates();
        assertThat(listed).isSorted();
    }

    @Test
    void mineAndCanDeleteDependOnTheViewer() throws Exception {
        TestUser author = user();
        TestUser commenter = user();
        TestUser reader = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        commentId(commenter, ratingId, "hello");

        comments(commenter, ratingId, null).andExpect(jsonPath("$.items[0].mine").value(true))
                .andExpect(jsonPath("$.items[0].canDelete").value(true));
        comments(author, ratingId, null).andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[0].canDelete").value(true));
        comments(reader, ratingId, null).andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[0].canDelete").value(false));
    }

    @Test
    void commentsByPeopleBlockedEitherWayOrSuspendedAreHidden() throws Exception {
        TestUser author = user();
        TestUser viewer = user();
        TestUser blockedByViewer = user();
        TestUser blockerOfViewer = user();
        TestUser suspended = user();
        TestUser fine = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        String hidden1 = commentId(blockedByViewer, ratingId, "a");
        String hidden2 = commentId(blockerOfViewer, ratingId, "b");
        String hidden3 = commentId(suspended, ratingId, "c");
        String shown = commentId(fine, ratingId, "d");
        block(viewer, blockedByViewer);
        block(blockerOfViewer, viewer);
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());

        assertThat(commentIds(viewer, ratingId)).containsExactly(shown);
        assertThat(commentCount(viewer, ratingId)).isEqualTo(1);
        assertThat(commentIds(author, ratingId)).containsExactly(hidden1, hidden2, shown);
        assertThat(commentCount(author, ratingId)).isEqualTo(3);
        assertThat(hidden3).isNotNull();
    }

    // ------------------------------------------------------------------------------- delete

    @Test
    void theCommentAuthorOrTheRatingAuthorMayDeleteOthersMayNot() throws Exception {
        TestUser author = user();
        TestUser commenter = user();
        TestUser reader = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        String byCommenter = commentId(commenter, ratingId, "one");
        String second = commentId(commenter, ratingId, "two");

        deleteComment(reader, byCommenter).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("You can only delete your own comments or comments on your ratings."));
        deleteComment(commenter, byCommenter).andExpect(status().isNoContent());
        deleteComment(commenter, byCommenter).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Comment not found."));
        deleteComment(author, second).andExpect(status().isNoContent());
        deleteComment(author, java.util.UUID.randomUUID().toString()).andExpect(status().isNotFound());
        deleteComment(author, "nope").andExpect(status().isBadRequest());
        assertThat(rows("select count(*) from app.rating_comments where rating_id = ?::uuid", ratingId)).isZero();
    }

    @Test
    void aCommentTheCallerCannotSeeIs404NotForbidden() throws Exception {
        TestUser author = user();
        TestUser commenter = user();
        TestUser outsider = user();
        befriend(author, commenter);
        String friendsOnly = review(author, newMovie(), "FRIENDS");
        String onFriendsOnly = commentId(commenter, friendsOnly, "x");
        String pub = review(author, newMovie(), "PUBLIC");
        String onPublic = commentId(commenter, pub, "y");
        block(outsider, commenter);

        deleteComment(outsider, onFriendsOnly).andExpect(status().isNotFound());
        deleteComment(outsider, onPublic).andExpect(status().isNotFound());
    }

    @Test
    void theRatingAuthorCanStillDeleteACommentBySomeoneTheyBlocked() throws Exception {
        TestUser author = user();
        TestUser troll = user();
        String pub = review(author, newMovie(), "PUBLIC");
        String nasty = commentId(troll, pub, "nasty");
        block(author, troll);

        assertThat(commentIds(author, pub)).isEmpty();
        deleteComment(author, nasty).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------- commentCount

    @Test
    void commentCountAppearsInEveryFeedListAndFollowsDeletes() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int movieId = newMovie();
        String ratingId = review(author, movieId, "PUBLIC");
        String uncommented = review(author, newMovie(), "PUBLIC");
        commentId(friend, ratingId, "a");
        String b = commentId(author, ratingId, "b");

        assertThat(friendsFeedItem(friend, ratingId).get("commentCount")).isEqualTo(2);
        assertThat(friendsFeedItem(friend, uncommented).get("commentCount")).isEqualTo(0);
        Map<String, Object> onMovie = all(c -> movieRatings(friend, movieId, FRIENDS, c)).getFirst();
        assertThat(onMovie.get("commentCount")).isEqualTo(2);
        Map<String, Object> onProfile = all(c -> userRatings(friend, author.id(), c)).stream()
                .filter(i -> ratingId.equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(onProfile.get("commentCount")).isEqualTo(2);
        Map<String, Object> everyone = all(c -> feed(friend, EVERYONE, c)).stream()
                .filter(i -> ratingId.equals(i.get("id"))).findFirst().orElseThrow();
        assertThat(everyone.get("commentCount")).isEqualTo(2);

        deleteComment(author, b).andExpect(status().isNoContent());
        assertThat(commentCount(friend, ratingId)).isEqualTo(1);
    }

    @Test
    void reRatingKeepsCommentsAndDeletingTheRatingRemovesThem() throws Exception {
        TestUser author = user();
        int movieId = newMovie();
        String ratingId = review(author, movieId, "PUBLIC");
        commentId(user(), ratingId, "first");

        assertThat(review(author, movieId, "PUBLIC", 3)).isEqualTo(ratingId);
        assertThat(commentCount(author, ratingId)).isEqualTo(1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/ratings/{id}", movieId).with(author.auth())).andExpect(status().isNoContent());
        assertThat(rows("select count(*) from app.rating_comments where rating_id = ?::uuid", ratingId)).isZero();
    }

    // ------------------------------------------------------------------------------- GET /ratings/{id}

    @Test
    void ratingDetailIsTheFeedItemForAnyoneWhoCanSeeIt() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(author, friend);
        int movieId = newMovie();
        String friendsOnly = review(author, movieId, "FRIENDS");
        String priv = review(author, newMovie(), "PRIVATE");
        String swipe = swipe(author, newMovie(), 5);
        like(friend, friendsOnly).andExpect(status().isNoContent());
        commentId(friend, friendsOnly, "nice");

        ratingDetail(friend, friendsOnly).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(friendsOnly))
                .andExpect(jsonPath("$.author.id").value(author.id().toString()))
                .andExpect(jsonPath("$.author.friendship").value("FRIENDS"))
                .andExpect(jsonPath("$.movie.id").value(movieId))
                .andExpect(jsonPath("$.certification").value("PG_13"))
                .andExpect(jsonPath("$.score").value(8))
                .andExpect(jsonPath("$.body").value("Thoughts on " + movieId))
                .andExpect(jsonPath("$.visibility").value("FRIENDS"))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true))
                .andExpect(jsonPath("$.commentCount").value(1))
                .andExpect(jsonPath("$.mine").value(false));
        ratingDetail(author, friendsOnly).andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.likedByMe").value(false));
        ratingDetail(author, priv).andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PRIVATE"));

        ratingDetail(stranger, friendsOnly).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Rating not found."));
        ratingDetail(friend, priv).andExpect(status().isNotFound());
        ratingDetail(author, swipe).andExpect(status().isNotFound());
        ratingDetail(friend, java.util.UUID.randomUUID().toString()).andExpect(status().isNotFound());
        ratingDetail(friend, "xyz").andExpect(status().isBadRequest());
        block(friend, author);
        ratingDetail(friend, friendsOnly).andExpect(status().isNotFound());
    }
}
