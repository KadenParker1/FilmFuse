package com.pm.filmfuse.social;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /blocks and how a block changes every other social read and write, in both directions. */
class BlockIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void blockRemovesFriendshipAndClosenessBothWays() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        befriend(me, bob);
        assertThat(closenessRows(me, bob)).isEqualTo(2);

        block(bob, me);

        assertThat(friendshipRows(me, bob)).isZero();
        assertThat(closenessRows(me, bob)).isZero();
        mockMvc.perform(get("/api/v1/friends").with(me.auth())).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/friends").with(bob.auth())).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void blockRemovesPendingRequestsInEitherDirection() throws Exception {
        TestUser me = user();
        TestUser outgoing = user();
        TestUser incoming = user();
        sendRequest(me, outgoing).andExpect(status().isOk());
        sendRequest(incoming, me).andExpect(status().isOk());

        block(me, outgoing);
        block(me, incoming);

        assertThat(friendshipRows(me, outgoing)).isZero();
        assertThat(friendshipRows(me, incoming)).isZero();
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(incoming.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listShowsBlockedUsersNewestFirstAsBlocked() throws Exception {
        TestUser me = user();
        TestUser first = user();
        TestUser second = user();
        block(me, first);
        block(me, second);
        jdbc.update("update app.blocks set created_at = now() - interval '1 hour' where blocker_id = ? and blocked_id = ?",
                me.id(), first.id());

        mockMvc.perform(get("/api/v1/blocks").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$[0].username").value(second.username()))
                .andExpect(jsonPath("$[0].friendship").value("BLOCKED"))
                .andExpect(jsonPath("$[1].id").value(first.id().toString()));
        // Being blocked is not visible in the blocked person's own list.
        mockMvc.perform(get("/api/v1/blocks").with(first.auth())).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void blockAndUnblockAreIdempotent() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        block(me, bob);
        block(me, bob);
        mockMvc.perform(get("/api/v1/blocks").with(me.auth())).andExpect(jsonPath("$", hasSize(1)));

        unblock(me, bob);
        unblock(me, bob);
        mockMvc.perform(get("/api/v1/blocks").with(me.auth())).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(delete("/api/v1/blocks/{id}", me.id()).with(me.auth())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/blocks/{id}", UUID.randomUUID()).with(me.auth())).andExpect(status().isNoContent());
    }

    @Test
    void blockingYourselfIs400AndAnUnknownUserIs404() throws Exception {
        TestUser me = user();
        mockMvc.perform(post("/api/v1/blocks/{id}", me.id()).with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't block yourself."));
        mockMvc.perform(post("/api/v1/blocks/{id}", UUID.randomUUID()).with(me.auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/blocks/{id}", "nope").with(me.auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unblockingDoesNotRestoreTheFriendship() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        befriend(me, bob);
        block(me, bob);
        unblock(me, bob);
        assertThat(stateOf(me, bob)).isEqualTo("NONE");
        assertThat(stateOf(bob, me)).isEqualTo("NONE");
    }

    @Test
    void callerWhoBlockedSeesBlockedAndMustUnblockBeforeRequesting() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        block(me, bob);

        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship").value("BLOCKED"));
        sendRequest(me, bob)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Unblock this person first."));
        assertThat(friendshipRows(me, bob)).isZero();

        unblock(me, bob);
        sendRequest(me, bob).andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
    }

    @Test
    void requestToSomeoneWhoBlockedYouLooksSentButStoresNothing() throws Exception {
        TestUser me = user();
        TestUser blocker = user();
        block(blocker, me);

        sendRequest(me, blocker)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(blocker.id().toString()))
                .andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
        // Again: still the same answer.
        sendRequest(me, blocker).andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));

        assertThat(friendshipRows(me, blocker)).isZero();
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "OUTGOING").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/friends/requests").param("direction", "INCOMING").with(blocker.auth()))
                .andExpect(jsonPath("$", hasSize(0)));
        verify(emailGateway, never()).send(any());
    }

    @Test
    void someoneWhoBlockedYouHasNoProfile() throws Exception {
        TestUser me = user();
        TestUser blocker = user();
        block(blocker, me);
        mockMvc.perform(get("/api/v1/users/{id}", blocker.id()).with(me.auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
        // The blocker still sees the blocked person, as BLOCKED.
        mockMvc.perform(get("/api/v1/users/{id}", me.id()).with(blocker.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship").value("BLOCKED"));
    }

    @Test
    void blockedUserCannotAcceptAnOldRequestFromTheBlocker() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        sendRequest(bob, me).andExpect(status().isOk());
        block(bob, me);
        accept(me, bob).andExpect(status().isNotFound());
        assertThat(friendshipRows(me, bob)).isZero();
    }

    @Test
    void mutualBlocksEachNeedTheirOwnUnblock() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        block(me, bob);
        block(bob, me);
        sendRequest(me, bob).andExpect(status().isConflict());
        unblock(me, bob);
        // Bob still blocks me: silent no-op now, and his profile is hidden.
        sendRequest(me, bob).andExpect(jsonPath("$.friendship").value("REQUEST_SENT"));
        assertThat(friendshipRows(me, bob)).isZero();
        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth())).andExpect(status().isNotFound());
    }
}
