package com.pm.filmfuse.social;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /friends/suggestions: friends of friends, ranked by mutual friends then username. */
class SuggestionIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void friendsOfFriendsRankedByMutualCountThenUsername() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        TestUser f1 = user(prefix + "_f1", null);
        TestUser f2 = user(prefix + "_f2", null);
        TestUser f3 = user(prefix + "_f3", null);
        TestUser f4 = user(prefix + "_f4", null);
        for (TestUser f : new TestUser[] {f1, f2, f3, f4}) {
            befriend(me, f);
        }
        // many: 4 mutual; zed: 2 mutual; amy: 2 mutual (beats zed on username); solo: 1 mutual.
        TestUser many = user(prefix + "_many", "Many Mutuals");
        TestUser zed = user(prefix + "_zed", null);
        TestUser amy = user(prefix + "_amy", null);
        TestUser solo = user(prefix + "_solo", null);
        for (TestUser f : new TestUser[] {f1, f2, f3, f4}) {
            befriend(f, many);
        }
        befriend(f1, zed);
        befriend(f2, zed);
        befriend(f3, amy);
        befriend(f4, amy);
        befriend(f1, solo);

        mockMvc.perform(get("/api/v1/friends/suggestions").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].user.id").value(many.id().toString()))
                .andExpect(jsonPath("$[0].user.username").value(many.username()))
                .andExpect(jsonPath("$[0].user.displayName").value("Many Mutuals"))
                .andExpect(jsonPath("$[0].user.friendship").value("NONE"))
                .andExpect(jsonPath("$[0].mutualFriendCount").value(4))
                .andExpect(jsonPath("$[0].mutualFriends", hasSize(3)))
                .andExpect(jsonPath("$[0].mutualFriends[0].friendship").value("FRIENDS"))
                .andExpect(jsonPath("$[1].user.id").value(amy.id().toString()))
                .andExpect(jsonPath("$[1].mutualFriendCount").value(2))
                .andExpect(jsonPath("$[1].mutualFriends[*].id",
                        containsInAnyOrder(f3.id().toString(), f4.id().toString())))
                .andExpect(jsonPath("$[2].user.id").value(zed.id().toString()))
                .andExpect(jsonPath("$[3].user.id").value(solo.id().toString()))
                .andExpect(jsonPath("$[3].mutualFriends", hasSize(1)))
                .andExpect(jsonPath("$[3].mutualFriends[0].id").value(f1.id().toString()));

        mockMvc.perform(get("/api/v1/friends/suggestions").param("limit", "2").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].user.id").value(amy.id().toString()));
    }

    @Test
    void mutualFriendSampleIsClosestFirst() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        TestUser a = user(prefix + "_a", null);
        TestUser b = user(prefix + "_b", null);
        TestUser c = user(prefix + "_c", null);
        TestUser d = user(prefix + "_d", null);
        TestUser candidate = user();
        for (TestUser f : new TestUser[] {a, b, c, d}) {
            befriend(me, f);
            befriend(f, candidate);
        }
        jdbc.update("update app.friend_closeness set explicit_close = true where user_id = ? and friend_id = ?", me.id(), d.id());
        jdbc.update("update app.friend_closeness set score = 0.7 where user_id = ? and friend_id = ?", me.id(), c.id());

        mockMvc.perform(get("/api/v1/friends/suggestions").with(me.auth()))
                .andExpect(jsonPath("$[0].mutualFriendCount").value(4))
                .andExpect(jsonPath("$[0].mutualFriends", hasSize(3)))
                .andExpect(jsonPath("$[0].mutualFriends[0].id").value(d.id().toString()))
                .andExpect(jsonPath("$[0].mutualFriends[1].id").value(c.id().toString()))
                .andExpect(jsonPath("$[0].mutualFriends[2].id").value(a.id().toString()));
    }

    @Test
    void excludesFriendsPendingBlockedNamelessSuspendedAndSelf() throws Exception {
        TestUser me = user();
        TestUser hub = user();
        befriend(me, hub);
        TestUser friend = user();
        TestUser sentTo = user();
        TestUser receivedFrom = user();
        TestUser iBlocked = user();
        TestUser blockedMe = user();
        TestUser nameless = userWithoutUsername();
        TestUser suspended = user();
        TestUser good = user();
        for (TestUser u : new TestUser[] {friend, sentTo, receivedFrom, iBlocked, blockedMe, nameless, suspended, good}) {
            befriend(hub, u);
        }
        befriend(me, friend);
        sendRequest(me, sentTo).andExpect(status().isOk());
        sendRequest(receivedFrom, me).andExpect(status().isOk());
        block(me, iBlocked);
        block(blockedMe, me);
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());

        mockMvc.perform(get("/api/v1/friends/suggestions").with(me.auth()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].user.id").value(good.id().toString()))
                .andExpect(jsonPath("$[0].mutualFriendCount").value(1));
    }

    @Test
    void noFriendsMeansNoSuggestions() throws Exception {
        TestUser me = user();
        TestUser stranger = user();
        TestUser strangersFriend = user();
        befriend(stranger, strangersFriend);
        mockMvc.perform(get("/api/v1/friends/suggestions").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void pendingRequestsDoNotCountAsMutualFriends() throws Exception {
        TestUser me = user();
        TestUser hub = user();
        TestUser candidate = user();
        sendRequest(me, hub).andExpect(status().isOk());
        befriend(hub, candidate);
        mockMvc.perform(get("/api/v1/friends/suggestions").with(me.auth())).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void limitMustBeBetween1And50() throws Exception {
        TestUser me = user();
        for (String limit : new String[] {"0", "51", "-1"}) {
            mockMvc.perform(get("/api/v1/friends/suggestions").param("limit", limit).with(me.auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.limit").value("Must be between 1 and 50"));
        }
        mockMvc.perform(get("/api/v1/friends/suggestions").param("limit", "ten").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.limit").value("Must be a whole number"));
        mockMvc.perform(get("/api/v1/friends/suggestions").param("limit", "1").with(me.auth()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/friends/suggestions").param("limit", "50").with(me.auth()))
                .andExpect(status().isOk());
    }
}
