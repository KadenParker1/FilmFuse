package com.pm.filmfuse.social;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /users/search and GET /users/{id}. */
class UserSearchIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void searchMatchesUsernamePrefixCaseInsensitively() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        TestUser anna = user(prefix + "_anna", "Anna Karenina");
        user("x" + prefix + "_notprefix", null);

        mockMvc.perform(get("/api/v1/users/search").param("q", prefix.toUpperCase()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(anna.id().toString()))
                .andExpect(jsonPath("$.items[0].username").value(anna.username()))
                .andExpect(jsonPath("$.items[0].displayName").value("Anna Karenina"))
                .andExpect(jsonPath("$.items[0].profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.items[0].friendship").value("NONE"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void searchMatchesDisplayNamePrefixCaseInsensitively() throws Exception {
        String unique = uniquePrefix();
        TestUser me = user();
        TestUser match = user(uniquePrefix(), "Zz" + unique + " Smith");
        user(uniquePrefix(), "Smith Zz" + unique);

        mockMvc.perform(get("/api/v1/users/search").param("q", "ZZ" + unique.toUpperCase()).with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(match.id().toString()));
        // Surrounding whitespace is ignored.
        mockMvc.perform(get("/api/v1/users/search").param("q", "  zz" + unique + "  ").with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void likeWildcardsAreLiteral() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        TestUser underscore = user(prefix + "_x", null);
        user(prefix + "ax", null);

        mockMvc.perform(get("/api/v1/users/search").param("q", prefix + "_").with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(underscore.id().toString()));
        mockMvc.perform(get("/api/v1/users/search").param("q", prefix + "%").with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/v1/users/search").param("q", "%%").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void searchExcludesSelfNamelessSuspendedAndPeopleWhoBlockedTheCaller() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user(prefix + "_me", null);
        TestUser visible = user(prefix + "_visible", null);
        TestUser blocker = user(prefix + "_blocker", null);
        TestUser suspended = user(prefix + "_suspended", null);
        user(null, prefix + " nameless");
        block(blocker, me);
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());

        mockMvc.perform(get("/api/v1/users/search").param("q", prefix).with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(visible.id().toString()));

        // The blocker still finds me, marked BLOCKED.
        mockMvc.perform(get("/api/v1/users/search").param("q", prefix + "_me").with(blocker.auth()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].friendship").value("BLOCKED"));
    }

    @Test
    void searchResultsCarryTheCallersFriendshipState() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        TestUser a = user(prefix + "_a_friend", null);
        TestUser b = user(prefix + "_b_sent", null);
        TestUser c = user(prefix + "_c_received", null);
        TestUser d = user(prefix + "_d_blocked", null);
        TestUser e = user(prefix + "_e_none", null);
        befriend(me, a);
        sendRequest(me, b).andExpect(status().isOk());
        sendRequest(c, me).andExpect(status().isOk());
        block(me, d);

        mockMvc.perform(get("/api/v1/users/search").param("q", prefix).with(me.auth()))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].id").value(a.id().toString()))
                .andExpect(jsonPath("$.items[0].friendship").value("FRIENDS"))
                .andExpect(jsonPath("$.items[1].friendship").value("REQUEST_SENT"))
                .andExpect(jsonPath("$.items[2].friendship").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$.items[3].friendship").value("BLOCKED"))
                .andExpect(jsonPath("$.items[4].id").value(e.id().toString()))
                .andExpect(jsonPath("$.items[4].friendship").value("NONE"));
    }

    @Test
    void searchPaginatesByUsernameTwentyAtATime() throws Exception {
        String prefix = uniquePrefix();
        TestUser me = user();
        List<String> usernames = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String username = prefix + "_%02d".formatted(i);
            jdbc.update("insert into app.profiles (id, username) values (?, ?)", UUID.randomUUID(), username);
            usernames.add(username);
        }

        String first = json(mockMvc.perform(get("/api/v1/users/search").param("q", prefix).with(me.auth()))
                .andExpect(status().isOk()));
        List<String> page1 = JsonPath.read(first, "$.items[*].username");
        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(page1).containsExactlyElementsOf(usernames.subList(0, 20));
        assertThat(cursor).isNotBlank().doesNotContain(prefix);

        String second = json(mockMvc.perform(get("/api/v1/users/search").param("q", prefix).param("cursor", cursor)
                .with(me.auth())).andExpect(status().isOk()));
        List<String> page2 = JsonPath.read(second, "$.items[*].username");
        assertThat(page2).containsExactlyElementsOf(usernames.subList(20, 25));
        assertThat((Object) JsonPath.read(second, "$.nextCursor")).isNull();
    }

    @Test
    void searchValidation() throws Exception {
        TestUser me = user();
        mockMvc.perform(get("/api/v1/users/search").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.q").exists());
        mockMvc.perform(get("/api/v1/users/search").param("q", "a").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.q").value("Search text must be 2-50 characters"));
        mockMvc.perform(get("/api/v1/users/search").param("q", "a".repeat(51)).with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.q").exists());
        mockMvc.perform(get("/api/v1/users/search").param("q", "   ").with(me.auth()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/users/search").param("q", " a ").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter at least 2 characters to search."));
        mockMvc.perform(get("/api/v1/users/search").param("q", "a".repeat(50)).with(me.auth()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/search").param("q", "ab").param("cursor", "%%%").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor."));
    }

    @Test
    void publicProfileShowsCountsAndState() throws Exception {
        TestUser me = user();
        TestUser target = user(uniquePrefix(), "Target Person");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/me")
                        .with(target.auth())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"bio\": \"Noir forever.\"}"))
                .andExpect(status().isOk());
        TestUser mutual1 = user();
        TestUser mutual2 = user();
        TestUser onlyTheirs = user();
        befriend(me, mutual1);
        befriend(me, mutual2);
        befriend(target, mutual1);
        befriend(target, mutual2);
        befriend(target, onlyTheirs);
        sendRequest(me, target).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/{id}", target.id()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.id().toString()))
                .andExpect(jsonPath("$.username").value(target.username()))
                .andExpect(jsonPath("$.displayName").value("Target Person"))
                .andExpect(jsonPath("$.bio").value("Noir forever."))
                .andExpect(jsonPath("$.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.friendship").value("REQUEST_SENT"))
                .andExpect(jsonPath("$.closeFriend").value(false))
                .andExpect(jsonPath("$.mutualFriendCount").value(2))
                .andExpect(jsonPath("$.friendCount").value(3))
                .andExpect(jsonPath("$.tasteOverlap").value(nullValue()))
                // Never leaked.
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());

        // Pending requests are not friends.
        mockMvc.perform(get("/api/v1/users/{id}", me.id()).with(target.auth()))
                .andExpect(jsonPath("$.friendship").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$.friendCount").value(2))
                .andExpect(jsonPath("$.mutualFriendCount").value(2));
    }

    @Test
    void ownProfileAndNamelessUsersAreViewableById() throws Exception {
        TestUser me = user();
        TestUser nameless = userWithoutUsername();
        befriend(me, nameless);

        mockMvc.perform(get("/api/v1/users/{id}", me.id()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendship").value("NONE"))
                .andExpect(jsonPath("$.friendCount").value(1))
                .andExpect(jsonPath("$.mutualFriendCount").value(0));
        mockMvc.perform(get("/api/v1/users/{id}", nameless.id()).with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(nullValue()))
                .andExpect(jsonPath("$.friendship").value("FRIENDS"));
    }

    @Test
    void unknownSuspendedOrMalformedProfileIds() throws Exception {
        TestUser me = user();
        TestUser suspended = user();
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID()).with(me.auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found."));
        mockMvc.perform(get("/api/v1/users/{id}", suspended.id()).with(me.auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/{id}", "not-a-uuid").with(me.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.id").value("Has an invalid value"));
    }
}
