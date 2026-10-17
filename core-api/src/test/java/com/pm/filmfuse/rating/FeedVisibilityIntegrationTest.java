package com.pm.filmfuse.rating;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every branch of the one visibility rule (RatingVisibilitySql), checked through every list that
 * uses it - {@code /feed} (both scopes), {@code /movies/{id}/ratings} (both scopes),
 * {@code /users/{id}/ratings} - and through the like check.
 */
class FeedVisibilityIntegrationTest extends AbstractFeedIntegrationTest {

    // ------------------------------------------------------------------------------- PRIVATE

    @Test
    void privateRatingsAreHiddenFromEveryoneElseButShownOnTheOwnProfile() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(author, friend);
        int movie = newMovie();
        String id = review(author, movie, "PRIVATE");

        for (TestUser viewer : List.of(friend, stranger)) {
            assertThat(friendsFeed(viewer)).doesNotContain(id);
            assertThat(everyone(viewer)).doesNotContain(id);
            assertThat(onMovie(viewer, movie, FRIENDS)).doesNotContain(id);
            assertThat(onMovie(viewer, movie, EVERYONE)).doesNotContain(id);
            assertThat(byUser(viewer, author)).doesNotContain(id);
            like(viewer, id).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Rating not found."));
        }

        // The author: only their own profile list shows it.
        assertThat(friendsFeed(author)).doesNotContain(id);
        assertThat(everyone(author)).doesNotContain(id);
        assertThat(onMovie(author, movie, FRIENDS)).doesNotContain(id);
        assertThat(onMovie(author, movie, EVERYONE)).doesNotContain(id);
        assertThat(byUser(author, author)).containsExactly(id);
        userRatings(author, author.id(), null)
                .andExpect(jsonPath("$.items[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.items[0].mine").value(true));
        // Visible to its author (on the profile), so the author may like it.
        like(author, id).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------- FRIENDS

    @Test
    void friendsRatingsAreVisibleToFriendsOnly() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(author, friend);
        int movie = newMovie();
        String id = review(author, movie, "FRIENDS");

        assertThat(friendsFeed(friend)).containsExactly(id);
        assertThat(onMovie(friend, movie, FRIENDS)).containsExactly(id);
        assertThat(byUser(friend, author)).containsExactly(id);
        // Not public, so not in EVERYONE - not even for a friend.
        assertThat(everyone(friend)).doesNotContain(id);
        assertThat(onMovie(friend, movie, EVERYONE)).isEmpty();
        like(friend, id).andExpect(status().isNoContent());

        assertThat(friendsFeed(stranger)).isEmpty();
        assertThat(everyone(stranger)).doesNotContain(id);
        assertThat(onMovie(stranger, movie, FRIENDS)).isEmpty();
        assertThat(onMovie(stranger, movie, EVERYONE)).isEmpty();
        assertThat(byUser(stranger, author)).isEmpty();
        like(stranger, id).andExpect(status().isNotFound());
    }

    @Test
    void aPendingRequestIsNotAFriendship() throws Exception {
        TestUser author = user();
        TestUser requester = user();
        sendRequest(requester, author).andExpect(status().isOk());
        String id = review(author, newMovie(), "FRIENDS");

        assertThat(friendsFeed(requester)).isEmpty();
        assertThat(byUser(requester, author)).isEmpty();
        like(requester, id).andExpect(status().isNotFound());
    }

    @Test
    void ownFriendsOnlyRatingsAreInTheOwnFriendsFeed() throws Exception {
        TestUser me = user();
        int movie = newMovie();
        String id = review(me, movie, "FRIENDS");

        assertThat(friendsFeed(me)).containsExactly(id);
        assertThat(onMovie(me, movie, FRIENDS)).containsExactly(id);
        assertThat(byUser(me, me)).containsExactly(id);
        assertThat(everyone(me)).doesNotContain(id);
    }

    @Test
    void unfriendingHidesFriendsOnlyRatingsButNotPublicOnes() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int movie = newMovie();
        String friendsOnly = review(author, movie, "FRIENDS");
        String everyoneSees = review(author, newMovie(), "PUBLIC");
        assertThat(friendsFeed(friend)).containsExactly(everyoneSees, friendsOnly);

        mockMvc.perform(delete("/api/v1/friends/{id}", author.id()).with(friend.auth()))
                .andExpect(status().isNoContent());

        assertThat(friendsFeed(friend)).isEmpty();
        assertThat(onMovie(friend, movie, FRIENDS)).isEmpty();
        assertThat(byUser(friend, author)).containsExactly(everyoneSees);
        assertThat(everyone(friend)).contains(everyoneSees).doesNotContain(friendsOnly);
        like(friend, friendsOnly).andExpect(status().isNotFound());
        like(friend, everyoneSees).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------------------- PUBLIC

    @Test
    void publicRatingsAreInEveryoneForAnybodyAndInFriendsOnlyForFriends() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        TestUser stranger = user();
        befriend(author, friend);
        int movie = newMovie();
        String id = review(author, movie, "PUBLIC");

        for (TestUser viewer : List.of(author, friend, stranger)) {
            assertThat(everyone(viewer)).contains(id);
            assertThat(onMovie(viewer, movie, EVERYONE)).containsExactly(id);
            assertThat(byUser(viewer, author)).containsExactly(id);
            like(viewer, id).andExpect(status().isNoContent());
        }
        // FRIENDS scope = the caller's and their friends' ratings, whatever their visibility.
        assertThat(friendsFeed(author)).containsExactly(id);
        assertThat(friendsFeed(friend)).containsExactly(id);
        assertThat(onMovie(friend, movie, FRIENDS)).containsExactly(id);
        assertThat(friendsFeed(stranger)).isEmpty();
        assertThat(onMovie(stranger, movie, FRIENDS)).isEmpty();
    }

    @Test
    void theFriendsFeedMixesOwnAndFriendsRatingsNewestFirst() throws Exception {
        TestUser me = user();
        TestUser friendA = user();
        TestUser friendB = user();
        TestUser stranger = user();
        befriend(me, friendA);
        befriend(friendB, me);
        String a = review(friendA, newMovie(), "FRIENDS");
        String mine = review(me, newMovie(), "PUBLIC");
        String strangers = review(stranger, newMovie(), "PUBLIC");
        String b = review(friendB, newMovie(), "PUBLIC");
        review(friendB, newMovie(), "PRIVATE");

        assertThat(friendsFeed(me)).containsExactly(b, mine, a).doesNotContain(strangers);
    }

    // ------------------------------------------------------------------------------- swipes

    @Test
    void calibrationSwipesAreNeverShownAnywhereEvenIfMarkedPublic() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int movie = newMovie();
        String id = swipe(author, movie, 9);
        // Swipes are always PRIVATE; force the worst case to prove the source check alone hides them.
        jdbc.update("update app.ratings set visibility = 'PUBLIC' where id = ?::uuid", id);

        for (TestUser viewer : List.of(author, friend)) {
            assertThat(friendsFeed(viewer)).doesNotContain(id);
            assertThat(everyone(viewer)).doesNotContain(id);
            assertThat(onMovie(viewer, movie, FRIENDS)).isEmpty();
            assertThat(onMovie(viewer, movie, EVERYONE)).isEmpty();
            assertThat(byUser(viewer, author)).isEmpty();
            like(viewer, id).andExpect(status().isNotFound());
        }
    }

    @Test
    void aSwipeTurnedIntoAReviewAppears() throws Exception {
        TestUser author = user();
        int movie = newMovie();
        String swiped = swipe(author, movie, 6);
        assertThat(byUser(author, author)).isEmpty();

        String reviewed = review(author, movie, "PUBLIC");

        assertThat(reviewed).isEqualTo(swiped);
        assertThat(onMovie(user(), movie, EVERYONE)).containsExactly(reviewed);
    }

    // ------------------------------------------------------------------------------- blocks

    @Test
    void aBlockHidesRatingsBothWays() throws Exception {
        TestUser blocker = user();
        TestUser blocked = user();
        int movie = newMovie();
        String byBlocker = review(blocker, movie, "PUBLIC");
        String byBlocked = review(blocked, movie, "PUBLIC");
        like(blocked, byBlocker).andExpect(status().isNoContent());

        block(blocker, blocked);

        assertHidden(blocked, blocker, movie, byBlocker);
        assertHidden(blocker, blocked, movie, byBlocked);
        // A third person still sees both.
        assertThat(onMovie(user(), movie, EVERYONE)).containsExactlyInAnyOrder(byBlocker, byBlocked);

        unblock(blocker, blocked);
        assertThat(onMovie(blocked, movie, EVERYONE)).containsExactly(byBlocked, byBlocker);
        assertThat(byUser(blocked, blocker)).containsExactly(byBlocker);
    }

    @Test
    void aBlockAlsoHidesFriendsOnlyRatingsOfFormerFriends() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        String byA = review(a, newMovie(), "FRIENDS");
        String byB = review(b, newMovie(), "FRIENDS");
        assertThat(friendsFeed(a)).containsExactly(byB, byA);

        block(b, a);

        assertThat(friendsFeed(a)).containsExactly(byA);
        assertThat(friendsFeed(b)).containsExactly(byB);
        like(a, byB).andExpect(status().isNotFound());
        like(b, byA).andExpect(status().isNotFound());
    }

    private void assertHidden(TestUser viewer, TestUser author, int movie, String ratingId) throws Exception {
        assertThat(friendsFeed(viewer)).doesNotContain(ratingId);
        assertThat(everyone(viewer)).doesNotContain(ratingId);
        assertThat(onMovie(viewer, movie, EVERYONE)).doesNotContain(ratingId);
        assertThat(onMovie(viewer, movie, FRIENDS)).doesNotContain(ratingId);
        userRatings(viewer, author.id(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
        like(viewer, ratingId).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------- suspended / unknown

    @Test
    void aSuspendedAuthorsRatingsAreHidden() throws Exception {
        TestUser author = user();
        TestUser friend = user();
        befriend(author, friend);
        int movie = newMovie();
        String friendsOnly = review(author, movie, "FRIENDS");
        String everyoneSees = review(author, newMovie(), "PUBLIC");
        assertThat(friendsFeed(friend)).containsExactly(everyoneSees, friendsOnly);

        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", author.id());

        assertThat(friendsFeed(friend)).isEmpty();
        assertThat(everyone(friend)).doesNotContain(everyoneSees, friendsOnly);
        assertThat(onMovie(friend, movie, FRIENDS)).isEmpty();
        userRatings(friend, author.id(), null).andExpect(status().isNotFound());
        like(friend, everyoneSees).andExpect(status().isNotFound());
        like(friend, friendsOnly).andExpect(status().isNotFound());
    }

    @Test
    void unknownUsersAndMoviesAre404() throws Exception {
        TestUser me = user();
        userRatings(me, java.util.UUID.randomUUID(), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
        movieRatings(me, -7, FRIENDS, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Movie not found."));
        like(me, java.util.UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void aMovieWithNoVisibleRatingsIsAnEmptyPage() throws Exception {
        movieRatings(user(), newMovie(), EVERYONE, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }
}
