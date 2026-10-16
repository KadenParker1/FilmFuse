package com.pm.filmfuse.social;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.email.EmailGateway;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.support.MutableClock;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the social integration tests: several signed-in users per test and helpers for the
 * common graph moves. Every social test class extends this, so they all share one Spring context
 * (the clock and the email-gateway spy are part of it).
 *
 * <p>The database is shared across the whole suite and never wiped, so every test makes its own
 * users, with a random username prefix wherever a search could see other tests' users.
 */
public abstract class AbstractSocialIntegrationTest extends AbstractIntegrationTest {

    protected static final MutableClock CLOCK = new MutableClock();

    @TestBean(name = "clock")
    protected Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @MockitoSpyBean
    protected EmailGateway emailGateway;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    /** A signed-in user; {@code username} null leaves them without one. */
    protected record TestUser(UUID id, String username, RequestPostProcessor auth) {
        public String email() {
            return JwtTestSupport.emailFor(id);
        }
    }

    @AfterEach
    void resetSocialState() {
        CLOCK.reset();
        clearInvocations(emailGateway);
    }

    /** A random lowercase prefix that no other test's usernames start with. */
    protected static String uniquePrefix() {
        StringBuilder prefix = new StringBuilder("t");
        for (int i = 0; i < 9; i++) {
            prefix.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return prefix.toString();
    }

    protected TestUser user() throws Exception {
        return user(uniquePrefix(), null);
    }

    protected TestUser userWithoutUsername() throws Exception {
        return user(null, null);
    }

    /** Provisions the profile through GET /me (which also stores the JWT email) and sets the names. */
    protected TestUser user(String username, String displayName) throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor auth = JwtTestSupport.supabaseUser(id);
        mockMvc.perform(get("/api/v1/me").with(auth)).andExpect(status().isOk());
        if (username != null || displayName != null) {
            StringBuilder body = new StringBuilder("{");
            if (username != null) {
                body.append("\"username\": \"").append(username).append("\"");
            }
            if (displayName != null) {
                body.append(username != null ? ", " : "").append("\"displayName\": \"").append(displayName).append("\"");
            }
            mockMvc.perform(patch("/api/v1/me").with(auth).contentType(MediaType.APPLICATION_JSON)
                            .content(body.append("}").toString()))
                    .andExpect(status().isOk());
        }
        return new TestUser(id, username, auth);
    }

    protected ResultActions sendRequest(TestUser from, TestUser to) throws Exception {
        return mockMvc.perform(post("/api/v1/friends/requests").with(from.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\": \"" + to.id() + "\"}"));
    }

    protected ResultActions accept(TestUser accepter, TestUser requester) throws Exception {
        return mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", requester.id()).with(accepter.auth()));
    }

    protected void befriend(TestUser a, TestUser b) throws Exception {
        sendRequest(a, b).andExpect(status().isOk());
        accept(b, a).andExpect(status().isOk());
    }

    protected void block(TestUser blocker, TestUser blocked) throws Exception {
        mockMvc.perform(post("/api/v1/blocks/{id}", blocked.id()).with(blocker.auth()))
                .andExpect(status().isNoContent());
    }

    protected void unblock(TestUser blocker, TestUser blocked) throws Exception {
        mockMvc.perform(delete("/api/v1/blocks/{id}", blocked.id()).with(blocker.auth()))
                .andExpect(status().isNoContent());
    }

    /** {@code viewer}'s FriendshipState for {@code other}, read from the public profile. */
    protected String stateOf(TestUser viewer, TestUser other) throws Exception {
        String json = mockMvc.perform(get("/api/v1/users/{id}", other.id()).with(viewer.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.friendship");
    }

    protected int friendshipRows(TestUser a, TestUser b) {
        Integer count = jdbc.queryForObject("""
                select count(*) from app.friendships
                where (user_low = ? and user_high = ?) or (user_low = ? and user_high = ?)
                """, Integer.class, a.id(), b.id(), b.id(), a.id());
        return count == null ? 0 : count;
    }

    protected int closenessRows(TestUser a, TestUser b) {
        Integer count = jdbc.queryForObject("""
                select count(*) from app.friend_closeness
                where (user_id = ? and friend_id = ?) or (user_id = ? and friend_id = ?)
                """, Integer.class, a.id(), b.id(), b.id(), a.id());
        return count == null ? 0 : count;
    }

    protected static String json(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
