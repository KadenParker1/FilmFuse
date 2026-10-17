package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.email.EmailMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every notification email (contract section E): who gets it, its text and link, and that each
 * preference switches exactly its own email off.
 */
class NotificationEmailIntegrationTest extends AbstractPhase5IntegrationTest {

    private static final String WEB = "http://localhost:8081";

    // ------------------------------------------------------------------------------- friend requests

    @Test
    void friendRequestEmailsFollowTheFriendRequestsPreference() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        TestUser carol = user();
        switchOff(bob, "friendRequests");
        switchOff(alice, "comments");
        clearEmails();

        sendRequest(alice, bob).andExpect(status().isOk());
        assertThat(sentEmails()).isEmpty();
        accept(bob, alice).andExpect(status().isOk());
        assertThat(emailsTo(alice)).singleElement()
                .satisfies(m -> assertThat(m.subject()).endsWith("accepted your friend request"));

        clearEmails();
        switchOff(alice, "friendRequests");
        sendRequest(carol, alice).andExpect(status().isOk());
        accept(alice, carol).andExpect(status().isOk());
        assertThat(emailsTo(alice)).isEmpty();
        assertThat(emailsTo(carol)).hasSize(1);
    }

    // ------------------------------------------------------------------------------- group invites

    @Test
    void creatingAGroupEmailsEachInvitee() throws Exception {
        TestUser owner = user("ow" + uniquePrefix(), "Olive");
        TestUser a = user();
        TestUser b = user();
        befriend(owner, a);
        befriend(owner, b);
        clearEmails();

        String groupId = JsonPath.read(json(createGroup(owner, "Film club", a, b).andExpect(status().isCreated())),
                "$.id");

        assertThat(emailsTo(owner)).isEmpty();
        for (TestUser invitee : List.of(a, b)) {
            EmailMessage message = emailsTo(invitee).getFirst();
            assertThat(emailsTo(invitee)).hasSize(1);
            assertThat(message.subject()).isEqualTo("Olive (@" + owner.username() + ") invited you to Film club");
            assertThat(message.body()).contains("\"Film club\"").contains(WEB + "/groups/" + groupId);
        }
        assertThat(sentEmails()).hasSize(2);
    }

    @Test
    void addingMembersEmailsOnlyTheNewInviteesWhoWantIt() throws Exception {
        TestUser owner = user();
        TestUser existing = user();
        TestUser fresh = user();
        TestUser quiet = user();
        UUID groupId = activeGroup(owner, existing);
        befriend(owner, fresh);
        befriend(owner, quiet);
        switchOff(quiet, "groupInvites");
        clearEmails();

        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\": [\"%s\", \"%s\", \"%s\"]}".formatted(existing.id(), fresh.id(), quiet.id())))
                .andExpect(status().isOk());

        assertThat(sentEmails()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo(fresh.email());
            assertThat(m.subject()).endsWith("invited you to Movie night");
            assertThat(m.body()).contains(WEB + "/groups/" + groupId);
        });
    }

    @Test
    void aGroupWithNoInviteesOrAFailedCreateSendsNothing() throws Exception {
        TestUser owner = user();
        TestUser stranger = user();
        clearEmails();
        createGroup(owner, "Solo").andExpect(status().isCreated());
        createGroup(owner, "Nope", stranger).andExpect(status().isBadRequest());
        assertThat(sentEmails()).isEmpty();
    }

    // ------------------------------------------------------------------------------- session started

    @Test
    void aGroupSessionEmailsEveryMemberButTheCreator() throws Exception {
        TestUser owner = user();
        TestUser a = user();
        TestUser b = user();
        TestUser quiet = user();
        UUID groupId = activeGroup(owner, a, b, quiet);
        switchOff(quiet, "sessionStarted");
        int genre = uniqueGenre();
        movie(genre, "PG", 1);
        clearEmails();

        UUID sessionId = sessionId(createGroupSession(a, groupId, genre));

        assertThat(sentEmails()).extracting(EmailMessage::to).containsExactlyInAnyOrder(owner.email(), b.email());
        assertThat(emailsTo(owner).getFirst().subject()).endsWith("started a movie session");
        assertThat(emailsTo(owner).getFirst().body()).contains(" in Movie night")
                .contains(WEB + "/sessions/" + sessionId);
    }

    @Test
    void anAdHocSessionEmailsTheInvitedFriends() throws Exception {
        TestUser host = user(uniquePrefix(), null);
        TestUser friend = user();
        befriend(host, friend);
        int genre = uniqueGenre();
        movie(genre, "PG", 1);
        clearEmails();

        UUID sessionId = sessionId(createAdHocSession(host, genre, friend));

        assertThat(sentEmails()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo(friend.email());
            assertThat(m.subject()).isEqualTo("@" + host.username() + " started a movie session");
            assertThat(m.body()).doesNotContain(" in ").contains(WEB + "/sessions/" + sessionId);
        });
    }

    @Test
    void votesPicksAndRefreshesSendNoSessionEmails() throws Exception {
        TestUser host = user();
        TestUser friend = user();
        befriend(host, friend);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(host, genre, friend));
        clearEmails();

        vote(friend, sessionId, movie, "UP").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/sessions/{id}/refresh", sessionId).with(host.auth())).andExpect(status().isOk());
        pick(host, sessionId, movie).andExpect(status().isOk());
        assertThat(sentEmails()).isEmpty();
    }

    // ------------------------------------------------------------------------------- comments

    @Test
    void aCommentEmailsTheRatingAuthorWithALinkToTheRating() throws Exception {
        TestUser author = user();
        TestUser commenter = user("cm" + uniquePrefix(), "Cam");
        int movieId = newMovie();
        String ratingId = review(author, movieId, "PUBLIC");
        clearEmails();

        commentId(commenter, ratingId, "secret plot twist");

        assertThat(sentEmails()).singleElement().satisfies(m -> {
            assertThat(m.to()).isEqualTo(author.email());
            assertThat(m.subject()).isEqualTo("Cam (@" + commenter.username() + ") commented on your rating of Movie "
                    + movieId);
            assertThat(m.body()).contains(WEB + "/ratings/" + ratingId).doesNotContain("secret plot twist");
        });
    }

    @Test
    void noCommentEmailForTheAuthorsOwnCommentOrWhenSwitchedOff() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        clearEmails();
        commentId(author, ratingId, "replying to myself");
        assertThat(sentEmails()).isEmpty();

        switchOff(author, "comments");
        commentId(user(), ratingId, "hello");
        assertThat(sentEmails()).isEmpty();
    }

    @Test
    void aRefusedCommentSendsNothing() throws Exception {
        TestUser author = user();
        String priv = review(author, newMovie(), "PRIVATE");
        clearEmails();
        comment(user(), priv, "hi").andExpect(status().isNotFound());
        comment(user(), review(author, newMovie(), "PUBLIC"), " ").andExpect(status().isBadRequest());
        assertThat(sentEmails()).isEmpty();
    }

    @Test
    void theCommentEmailGoesOutOnlyAfterCommitAndAFailureDoesNotFailTheRequest() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        List<Integer> visibleElsewhere = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            visibleElsewhere.add(CompletableFuture.supplyAsync(() -> rows(
                    "select count(*) from app.rating_comments where rating_id = ?::uuid", ratingId)).get());
            throw new MailSendException("relay down");
        }).when(emailGateway).send(any());

        comment(user(), ratingId, "first").andExpect(status().isCreated());

        assertThat(visibleElsewhere).containsExactly(1);
    }

    @Test
    void aRecipientWithoutAnAddressIsSkipped() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        jdbc.update("update app.profiles set email = null where id = ?", author.id());
        TestUser commenter = user();
        clearEmails();

        comment(commenter, ratingId, "hi").andExpect(status().isCreated());
        assertThat(sentEmails()).isEmpty();
    }
}
