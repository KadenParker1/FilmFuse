package com.pm.filmfuse.social;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST/GET/DELETE /friends/requests and accept / decline. */
class FriendRequestIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void sendStoresAPendingRequestVisibleToBothSides() throws Exception {
        TestUser alice = user();
        TestUser bob = user();

        sendRequest(alice, bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(bob.id().toString()))
                .andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));

        assertThat(stateOf(alice, bob)).isEqualTo("REQUEST_SENT");
        assertThat(stateOf(bob, alice)).isEqualTo("REQUEST_RECEIVED");

        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].direction").value("OUTGOING"))
                .andExpect(jsonPath("$[0].user.id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].user.username").value(bob.username()))
                .andExpect(jsonPath("$[0].user.friendship").value("REQUEST_SENT"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(bob.auth()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].direction").value("INCOMING"))
                .andExpect(jsonPath("$[0].user.id").value(alice.id().toString()))
                .andExpect(jsonPath("$[0].user.friendship").value("REQUEST_RECEIVED"));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(alice.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(bob.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void requestsAreListedNewestFirst() throws Exception {
        TestUser me = user();
        TestUser first = user();
        TestUser second = user();
        TestUser third = user();
        sendRequest(first, me).andExpect(status().isOk());
        sendRequest(second, me).andExpect(status().isOk());
        sendRequest(third, me).andExpect(status().isOk());
        // now() is the transaction start; make the order unambiguous.
        jdbc.update("update app.friendships set created_at = now() - interval '3 minutes' where requested_by = ?", first.id());
        jdbc.update("update app.friendships set created_at = now() - interval '1 minutes' where requested_by = ?", third.id());
        jdbc.update("update app.friendships set created_at = now() - interval '2 minutes' where requested_by = ?", second.id());

        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].user.id").value(third.id().toString()))
                .andExpect(jsonPath("$[1].user.id").value(second.id().toString()))
                .andExpect(jsonPath("$[2].user.id").value(first.id().toString()));
    }

    @Test
    void sendingTwiceIsIdempotent() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());
        sendRequest(alice, bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
        assertThat(friendshipRows(alice, bob)).isEqualTo(1);
    }

    @Test
    void sendingWhenTheyAlreadyAskedYouAutoAccepts() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());

        sendRequest(bob, alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(alice.id().toString()))
                .andExpect(jsonPath("$.friendship").value("FRIENDS"));

        assertThat(stateOf(alice, bob)).isEqualTo("FRIENDS");
        assertThat(stateOf(bob, alice)).isEqualTo("FRIENDS");
        assertThat(closenessRows(alice, bob)).isEqualTo(2);
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(alice.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void acceptMakesFriendsWithClosenessRowsBothWays() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());

        accept(bob, alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(alice.id().toString()))
                .andExpect(jsonPath("$.friendship").value("FRIENDS"));

        assertThat(stateOf(alice, bob)).isEqualTo("FRIENDS");
        assertThat(closenessRows(alice, bob)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select accepted_at is not null from app.friendships
                where requested_by = ? and status = 'ACCEPTED'""", Boolean.class, alice.id())).isTrue();
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(alice.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void acceptWithoutAPendingRequestIs404() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        accept(bob, alice)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("There is no pending friend request from this person."));

        // The requester cannot accept their own request.
        sendRequest(alice, bob).andExpect(status().isOk());
        accept(alice, bob).andExpect(status().isNotFound());
        assertThat(stateOf(alice, bob)).isEqualTo("REQUEST_SENT");

        // Already friends: there is no pending request any more.
        accept(bob, alice).andExpect(status().isOk());
        accept(bob, alice).andExpect(status().isNotFound());

        // Oneself, and an unknown id.
        mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", alice.id()).with(alice.auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", UUID.randomUUID()).with(alice.auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void declineRemovesTheRequestAndIsIdempotent() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", alice.id()).with(bob.auth()))
                .andExpect(status().isNoContent());
        assertThat(friendshipRows(alice, bob)).isZero();
        assertThat(stateOf(alice, bob)).isEqualTo("NONE");

        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", alice.id()).with(bob.auth()))
                .andExpect(status().isNoContent());
        // Alice may ask again.
        sendRequest(alice, bob).andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
    }

    @Test
    void declineCannotRemoveTheCallersOwnRequestOrAFriendship() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());
        // Alice "declining" Bob: there is no request from Bob, so nothing happens.
        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", bob.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        assertThat(stateOf(alice, bob)).isEqualTo("REQUEST_SENT");

        accept(bob, alice).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", alice.id()).with(bob.auth()))
                .andExpect(status().isNoContent());
        assertThat(stateOf(alice, bob)).isEqualTo("FRIENDS");
    }

    @Test
    void cancelWithdrawsOnlyTheCallersOwnRequestAndIsIdempotent() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());

        // Bob cannot "cancel" Alice's request to him.
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", alice.id()).with(bob.auth()))
                .andExpect(status().isNoContent());
        assertThat(stateOf(bob, alice)).isEqualTo("REQUEST_RECEIVED");

        mockMvc.perform(delete("/api/v1/friends/requests/{id}", bob.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        assertThat(friendshipRows(alice, bob)).isZero();
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", bob.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", alice.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelDoesNotUnfriend() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        befriend(alice, bob);
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", bob.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        assertThat(stateOf(alice, bob)).isEqualTo("FRIENDS");
    }

    @Test
    void sendingToAFriendIs409() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        befriend(alice, bob);
        sendRequest(alice, bob)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You are already friends."));
        sendRequest(bob, alice).andExpect(status().isConflict());
    }

    @Test
    void sendingToYourselfIs400() throws Exception {
        TestUser alice = user();
        sendRequest(alice, alice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't send a friend request to yourself."));
        assertThat(friendshipRows(alice, alice)).isZero();
    }

    @Test
    void sendingToAnUnknownUserIs404() throws Exception {
        TestUser alice = user();
        mockMvc.perform(post("/api/v1/friends/requests").with(alice.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
    }

    @Test
    void sendingToASuspendedUserIs404() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", bob.id());
        sendRequest(alice, bob).andExpect(status().isNotFound());
    }

    @Test
    void requestsWithAnAccountSuspendedSinceAreNotListed() throws Exception {
        TestUser me = user();
        TestUser suspendedSender = user();
        TestUser suspendedTarget = user();
        TestUser activeSender = user();
        sendRequest(suspendedSender, me).andExpect(status().isOk());
        sendRequest(activeSender, me).andExpect(status().isOk());
        sendRequest(me, suspendedTarget).andExpect(status().isOk());
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id in (?, ?)",
                suspendedSender.id(), suspendedTarget.id());

        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].user.id").value(activeSender.id().toString()));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(0)));

        // Lifting the suspension brings the request back; the row itself was kept.
        jdbc.update("update app.profiles set status = 'ACTIVE' where id = ?", suspendedSender.id());
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void usersWithoutAUsernameCanStillBeAskedById() throws Exception {
        TestUser alice = user();
        TestUser nameless = userWithoutUsername();
        sendRequest(alice, nameless).andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(nameless.auth()))
                .andExpect(jsonPath("$[0].user.id").value(alice.id().toString()));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(alice.auth()))
                .andExpect(jsonPath("$[0].user.username").value(nullValue()));
    }

    @Test
    void sendValidation() throws Exception {
        TestUser alice = user();
        mockMvc.perform(post("/api/v1/friends/requests").with(alice.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.targetUserId").exists());
        mockMvc.perform(post("/api/v1/friends/requests").with(alice.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\": \"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body could not be read as JSON."));
        mockMvc.perform(post("/api/v1/friends/requests").with(alice.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pathIdsMustBeUuids() throws Exception {
        TestUser alice = user();
        mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", "nope").with(alice.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.requesterId").value("Has an invalid value"));
        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", "nope").with(alice.auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", "nope").with(alice.auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void directionIsRequiredAndMustBeIncomingOrOutgoing() throws Exception {
        TestUser alice = user();
        mockMvc.perform(get("/api/v1/friends/requests").with(alice.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.direction").exists());
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "SIDEWAYS").with(alice.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.direction").value("Has an invalid value"));
    }
}
