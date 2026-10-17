package com.pm.filmfuse.rating;

import com.pm.filmfuse.email.EmailMessage;
import com.pm.filmfuse.rating.service.DigestJob;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** The weekly digest (contract section E): opt-in, content, ordering, visibility and skipping. */
class DigestIntegrationTest extends AbstractPhase5IntegrationTest {

    @Autowired
    private DigestJob digestJob;

    private EmailMessage digestFor(TestUser user) {
        digestJob.sendAll();
        var mine = emailsTo(user);
        assertThat(mine).hasSize(1);
        return mine.getFirst();
    }

    @Test
    void theTopFiveVisibleFriendRatingsOfTheWeekBestFirst() throws Exception {
        TestUser reader = user();
        TestUser f1 = user("fa" + uniquePrefix(), "Faye");
        TestUser f2 = user();
        TestUser stranger = user();
        befriend(reader, f1);
        befriend(reader, f2);
        optInToDigest(reader);

        int[] scores = {6, 10, 3, 9, 7, 8};
        String[] ids = new String[scores.length];
        int[] movies = new int[scores.length];
        for (int i = 0; i < scores.length; i++) {
            movies[i] = newMovie();
            ids[i] = review(i % 2 == 0 ? f1 : f2, movies[i], i % 3 == 0 ? "PUBLIC" : "FRIENDS", scores[i]);
        }
        review(reader, newMovie(), "PUBLIC", 10);
        review(stranger, newMovie(), "PUBLIC", 10);
        review(f1, newMovie(), "PRIVATE", 10);
        swipe(f2, newMovie(), 10);
        clearEmails();

        EmailMessage digest = digestFor(reader);

        assertThat(digest.to()).isEqualTo(reader.email());
        assertThat(digest.subject()).isEqualTo("Your friends' top movies this week");
        String body = digest.body();
        // 10 (f2), 9 (f2), 8 (f2), 7 (f1), 6 (f1); the 3 is the sixth and is left out.
        int[] expectedOrder = {1, 3, 5, 4, 0};
        int last = -1;
        for (int index : expectedOrder) {
            String line = "rated Movie " + movies[index] + " " + scores[index] + "/10";
            assertThat(body).contains(line).contains("http://localhost:8081/ratings/" + ids[index]);
            assertThat(body.indexOf(line)).isGreaterThan(last);
            last = body.indexOf(line);
        }
        assertThat(body).doesNotContain("Movie " + movies[2]).doesNotContain("Thoughts on")
                .contains("Faye (@" + f1.username() + ") rated").contains("/settings/notifications");
        assertThat(body.split("/ratings/", -1)).hasSize(6);
    }

    @Test
    void onlyTheLastSevenDays() throws Exception {
        TestUser reader = user();
        TestUser friend = user();
        befriend(reader, friend);
        optInToDigest(reader);
        int old = newMovie();
        review(friend, old, "FRIENDS", 10);
        CLOCK.advance(Duration.ofDays(7).plusMinutes(1));
        int recent = newMovie();
        review(friend, recent, "FRIENDS", 4);
        clearEmails();

        String body = digestFor(reader).body();
        assertThat(body).contains("Movie " + recent).doesNotContain("Movie " + old);
    }

    @Test
    void optedOutUsersAndUsersWithNothingToShowGetNoEmail() throws Exception {
        TestUser defaultUser = user();
        TestUser optedOut = user();
        TestUser lonely = user();
        TestUser noAddress = user();
        TestUser suspended = user();
        TestUser friend = user();
        for (TestUser u : new TestUser[] {defaultUser, optedOut, noAddress, suspended}) {
            befriend(u, friend);
        }
        optInToDigest(optedOut);
        switchOff(optedOut, "comments");
        optInToDigest(lonely);
        optInToDigest(noAddress);
        optInToDigest(suspended);
        jdbc.update("update app.profiles set email = null where id = ?", noAddress.id());
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());
        review(friend, newMovie(), "PUBLIC", 9);
        clearEmails();

        digestJob.sendAll();

        for (TestUser u : new TestUser[] {defaultUser, optedOut, lonely, noAddress, suspended}) {
            assertThat(emailsTo(u)).as(u.id().toString()).isEmpty();
        }
    }

    @Test
    void blockedAndExFriendsDropOut() throws Exception {
        TestUser reader = user();
        TestUser blocked = user();
        TestUser exFriend = user();
        TestUser friend = user();
        befriend(reader, blocked);
        befriend(reader, exFriend);
        befriend(reader, friend);
        optInToDigest(reader);
        int fromBlocked = newMovie();
        int fromEx = newMovie();
        int fromFriend = newMovie();
        review(blocked, fromBlocked, "PUBLIC", 9);
        review(exFriend, fromEx, "FRIENDS", 9);
        review(friend, fromFriend, "FRIENDS", 2);
        block(reader, blocked);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/friends/{id}", exFriend.id()).with(reader.auth()));
        clearEmails();

        String body = digestFor(reader).body();
        assertThat(body).contains("Movie " + fromFriend).doesNotContain("Movie " + fromBlocked)
                .doesNotContain("Movie " + fromEx);
    }

    @Test
    void sendAllCountsTheDigestsItSent() throws Exception {
        TestUser reader = user();
        TestUser friend = user();
        befriend(reader, friend);
        optInToDigest(reader);
        review(friend, newMovie(), "PUBLIC", 9);
        clearEmails();

        int sent = digestJob.sendAll();
        assertThat(sent).isEqualTo(sentEmails().size()).isPositive();
        assertThat(emailsTo(reader)).hasSize(1);
    }
}
