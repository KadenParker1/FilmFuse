package com.pm.filmfuse.social;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /friends, PATCH /friends/{id} (close friend) and DELETE /friends/{id} (unfriend). */
class FriendIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void listShowsFriendsOnlyWithDefaultCloseness() throws Exception {
        TestUser me = user();
        TestUser friend = user(uniquePrefix(), "Friendly Fran");
        TestUser pending = user();
        befriend(me, friend);
        sendRequest(me, pending).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/friends").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(friend.id().toString()))
                .andExpect(jsonPath("$.items[0].username").value(friend.username()))
                .andExpect(jsonPath("$.items[0].displayName").value("Friendly Fran"))
                .andExpect(jsonPath("$.items[0].profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.items[0].closeFriend").value(false))
                .andExpect(jsonPath("$.items[0].closeness").value(0.5))
                .andExpect(jsonPath("$.items[0].friendsSince").isNotEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        // Symmetric.
        mockMvc.perform(get("/api/v1/friends").with(friend.auth()))
                .andExpect(jsonPath("$.items[0].id").value(me.id().toString()));
    }

    @Test
    void emptyListForSomeoneWithNoFriends() throws Exception {
        mockMvc.perform(get("/api/v1/friends").with(user().auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void closeFriendToggleRaisesClosenessAndUnsettingRestoresIt() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        befriend(me, bob);

        patchClose(me, bob, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bob.id().toString()))
                .andExpect(jsonPath("$.closeFriend").value(true))
                .andExpect(jsonPath("$.closeness").value(0.8));

        // Directional: Bob's view of me is unchanged.
        mockMvc.perform(get("/api/v1/friends").with(bob.auth()))
                .andExpect(jsonPath("$.items[0].closeFriend").value(false))
                .andExpect(jsonPath("$.items[0].closeness").value(0.5));

        patchClose(me, bob, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeFriend").value(false))
                .andExpect(jsonPath("$.closeness").value(0.5));

        // A high implicit score is kept while close, and restored after.
        jdbc.update("update app.friend_closeness set score = 0.9 where user_id = ? and friend_id = ?", me.id(), bob.id());
        patchClose(me, bob, true).andExpect(jsonPath("$.closeness").value(0.9));
        patchClose(me, bob, false).andExpect(jsonPath("$.closeness").value(0.9));
        jdbc.update("update app.friend_closeness set score = 0.3 where user_id = ? and friend_id = ?", me.id(), bob.id());
        patchClose(me, bob, true).andExpect(jsonPath("$.closeness").value(0.8));
        patchClose(me, bob, false).andExpect(jsonPath("$.closeness").value(0.3));
    }

    @Test
    void publicProfileReportsTheCallersCloseFriendFlag() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        TestUser stranger = user();

        // Not friends: always false.
        mockMvc.perform(get("/api/v1/users/{id}", stranger.id()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship").value("NONE"))
                .andExpect(jsonPath("$.closeFriend").value(false));

        befriend(me, bob);
        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth()))
                .andExpect(jsonPath("$.friendship").value("FRIENDS"))
                .andExpect(jsonPath("$.closeFriend").value(false));

        patchClose(me, bob, true).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth()))
                .andExpect(jsonPath("$.closeFriend").value(true));
        // Directional: Bob has not flagged me.
        mockMvc.perform(get("/api/v1/users/{id}", me.id()).with(bob.auth()))
                .andExpect(jsonPath("$.closeFriend").value(false));

        patchClose(me, bob, false).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth()))
                .andExpect(jsonPath("$.closeFriend").value(false));

        // Unfriending a close friend clears it too.
        patchClose(me, bob, true).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/friends/{id}", bob.id()).with(me.auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/{id}", bob.id()).with(me.auth()))
                .andExpect(jsonPath("$.friendship").value("NONE"))
                .andExpect(jsonPath("$.closeFriend").value(false));
    }

    @Test
    void listIsOrderedCloseFirstThenClosenessThenUsername() throws Exception {
        TestUser me = user();
        String prefix = uniquePrefix();
        TestUser alpha = user(prefix + "_alpha", null);
        TestUser bravo = user(prefix + "_bravo", null);
        TestUser charlie = user(prefix + "_charlie", null);
        TestUser delta = user(prefix + "_delta", null);
        for (TestUser friend : List.of(alpha, bravo, charlie, delta)) {
            befriend(me, friend);
        }
        // delta: close friend; charlie: implicit 0.7; alpha/bravo: 0.5 (tie broken by username).
        patchClose(me, delta, true).andExpect(status().isOk());
        jdbc.update("update app.friend_closeness set score = 0.7 where user_id = ? and friend_id = ?", me.id(), charlie.id());
        // Charlie's implicit 0.7 is lower than delta's effective 0.8, but close friends come first regardless.
        jdbc.update("update app.friend_closeness set score = 0.95 where user_id = ? and friend_id = ?", me.id(), bravo.id());
        jdbc.update("update app.friend_closeness set score = 0.5 where user_id = ? and friend_id = ?", me.id(), alpha.id());

        mockMvc.perform(get("/api/v1/friends").with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[0].id").value(delta.id().toString()))
                .andExpect(jsonPath("$.items[1].id").value(bravo.id().toString()))
                .andExpect(jsonPath("$.items[2].id").value(charlie.id().toString()))
                .andExpect(jsonPath("$.items[3].id").value(alpha.id().toString()));
    }

    @Test
    void listPaginatesWithAnOpaqueCursor() throws Exception {
        TestUser me = user();
        String prefix = uniquePrefix();
        // 53 friends straight into the tables: the page size is 50.
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into app.profiles (id, username) values (?, ?)", id, prefix + "_%02d".formatted(i));
            UUID low = id.toString().compareTo(me.id().toString()) < 0 ? id : me.id();
            UUID high = low.equals(id) ? me.id() : id;
            jdbc.update("""
                    insert into app.friendships (user_low, user_high, status, requested_by, accepted_at)
                    values (?, ?, 'ACCEPTED', ?, now())""", low, high, me.id());
            jdbc.update("insert into app.friend_closeness (user_id, friend_id) values (?, ?), (?, ?)", me.id(), id, id, me.id());
            expected.add(id);
        }
        // Two close friends and one high-closeness friend shuffle the order across the page boundary.
        jdbc.update("update app.friend_closeness set explicit_close = true where user_id = ? and friend_id = ?", me.id(), expected.get(52));
        jdbc.update("update app.friend_closeness set explicit_close = true where user_id = ? and friend_id = ?", me.id(), expected.get(10));
        jdbc.update("update app.friend_closeness set score = 0.6 where user_id = ? and friend_id = ?", me.id(), expected.get(40));
        List<UUID> order = new ArrayList<>(expected);
        order.remove(expected.get(52));
        order.remove(expected.get(10));
        order.remove(expected.get(40));
        order.addAll(0, List.of(expected.get(10), expected.get(52), expected.get(40)));

        String first = json(mockMvc.perform(get("/api/v1/friends").with(me.auth())).andExpect(status().isOk()));
        List<String> page1 = JsonPath.read(first, "$.items[*].id");
        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(page1).hasSize(50);
        assertThat(cursor).isNotBlank();

        String second = json(mockMvc.perform(get("/api/v1/friends").param("cursor", cursor).with(me.auth()))
                .andExpect(status().isOk()));
        List<String> page2 = JsonPath.read(second, "$.items[*].id");
        assertThat((Object) JsonPath.read(second, "$.nextCursor")).isNull();

        List<String> all = new ArrayList<>(page1);
        all.addAll(page2);
        assertThat(all).containsExactlyElementsOf(order.stream().map(UUID::toString).toList());
    }

    @Test
    void invalidCursorIs400() throws Exception {
        TestUser me = user();
        for (String cursor : new String[] {"!!!", "bm9wZQ", "MXgweDB4"}) {
            mockMvc.perform(get("/api/v1/friends").param("cursor", cursor).with(me.auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid cursor."));
        }
    }

    @Test
    void patchingSomeoneWhoIsNotAFriendIs404() throws Exception {
        TestUser me = user();
        TestUser stranger = user();
        patchClose(me, stranger, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("You are not friends with this person."));
        sendRequest(me, stranger).andExpect(status().isOk());
        patchClose(me, stranger, true).andExpect(status().isNotFound());
        patchClose(me, me, true).andExpect(status().isNotFound());
        assertThat(closenessRows(me, stranger)).isZero();
    }

    @Test
    void patchValidation() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        befriend(me, bob);
        mockMvc.perform(patch("/api/v1/friends/{id}", bob.id()).with(me.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.closeFriend").value("closeFriend is required"));
        mockMvc.perform(patch("/api/v1/friends/{id}", "nope").with(me.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"closeFriend\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.userId").exists());
    }

    @Test
    void unfriendRemovesFriendshipAndClosenessAndIsIdempotent() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        befriend(me, bob);
        patchClose(me, bob, true).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/friends/{id}", bob.id()).with(me.auth()))
                .andExpect(status().isNoContent());
        assertThat(friendshipRows(me, bob)).isZero();
        assertThat(closenessRows(me, bob)).isZero();
        assertThat(stateOf(me, bob)).isEqualTo("NONE");

        mockMvc.perform(delete("/api/v1/friends/{id}", bob.id()).with(me.auth())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/friends/{id}", UUID.randomUUID()).with(me.auth())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/friends/{id}", me.id()).with(me.auth())).andExpect(status().isNoContent());

        // Re-befriending starts closeness again from the defaults.
        befriend(bob, me);
        mockMvc.perform(get("/api/v1/friends").with(me.auth()))
                .andExpect(jsonPath("$.items[0].closeFriend").value(false))
                .andExpect(jsonPath("$.items[0].closeness").value(0.5));
    }

    @Test
    void unfriendLeavesAPendingRequestAlone() throws Exception {
        TestUser me = user();
        TestUser bob = user();
        sendRequest(me, bob).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/friends/{id}", bob.id()).with(me.auth())).andExpect(status().isNoContent());
        assertThat(stateOf(me, bob)).isEqualTo("REQUEST_SENT");
    }

    private org.springframework.test.web.servlet.ResultActions patchClose(TestUser me, TestUser friend, boolean close)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/friends/{id}", friend.id()).with(me.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closeFriend\": " + close + "}"));
    }
}
