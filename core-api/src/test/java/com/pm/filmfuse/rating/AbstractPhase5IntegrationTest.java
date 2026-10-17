package com.pm.filmfuse.rating;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.email.EmailMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import com.pm.filmfuse.movie.catalog.MovieCatalogClient;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helpers for the Phase 5 tests (post-watch prompts, comments, reports, notification settings,
 * closeness, digest). Extends the feed base so the whole suite keeps sharing one Spring context.
 */
public abstract class AbstractPhase5IntegrationTest extends AbstractFeedIntegrationTest {

    /** A spy on the (fixture) catalog, so enrichment tests can stub details for their own movies. */
    @MockitoSpyBean
    protected MovieCatalogClient catalogClient;

    /** What a session pick produced. */
    protected record Watched(UUID sessionId, int movieId) {
    }

    /**
     * {@code picker} and {@code others} (already friends of {@code picker}) watch a new movie together
     * through an ad-hoc session; one second after the previous write.
     */
    protected Watched watchAdHoc(TestUser picker, TestUser... others) throws Exception {
        int genre = uniqueGenre();
        int movieId = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(picker, genre, others));
        CLOCK.advance(Duration.ofSeconds(1));
        pick(picker, sessionId, movieId).andExpect(status().isOk());
        return new Watched(sessionId, movieId);
    }

    /** As {@link #watchAdHoc} for a saved group's session. */
    protected Watched watchInGroup(TestUser picker, UUID groupId) throws Exception {
        int genre = uniqueGenre();
        int movieId = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createGroupSession(picker, groupId, genre));
        CLOCK.advance(Duration.ofSeconds(1));
        pick(picker, sessionId, movieId).andExpect(status().isOk());
        return new Watched(sessionId, movieId);
    }

    // ------------------------------------------------------------------------------- requests

    protected ResultActions pendingRatings(TestUser user) throws Exception {
        return mockMvc.perform(get("/api/v1/me/pending-ratings").with(user.auth()));
    }

    protected ResultActions dismissPending(TestUser user, int movieId) throws Exception {
        return mockMvc.perform(post("/api/v1/me/pending-ratings/{id}/dismiss", movieId).with(user.auth()));
    }

    protected ResultActions comment(TestUser user, String ratingId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ratings/{id}/comments", ratingId).with(user.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\": " + (body == null ? "null" : "\"" + body + "\"") + "}"));
    }

    /** Posts a comment (201) and returns its id. */
    protected String commentId(TestUser user, String ratingId, String body) throws Exception {
        return JsonPath.read(json(comment(user, ratingId, body).andExpect(status().isCreated())), "$.id");
    }

    protected ResultActions comments(TestUser viewer, String ratingId, String cursor) throws Exception {
        var request = get("/api/v1/ratings/{id}/comments", ratingId).with(viewer.auth());
        return mockMvc.perform(cursor == null ? request : request.param("cursor", cursor));
    }

    protected ResultActions deleteComment(TestUser user, String commentId) throws Exception {
        return mockMvc.perform(delete("/api/v1/comments/{id}", commentId).with(user.auth()));
    }

    protected ResultActions ratingDetail(TestUser viewer, String ratingId) throws Exception {
        return mockMvc.perform(get("/api/v1/ratings/{id}", ratingId).with(viewer.auth()));
    }

    protected ResultActions report(TestUser user, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/reports").with(user.auth()).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    protected ResultActions report(TestUser user, String type, Object targetId, String reason) throws Exception {
        return report(user, "{\"targetType\": \"%s\", \"targetId\": \"%s\", \"reason\": \"%s\"}"
                .formatted(type, targetId, reason));
    }

    protected ResultActions notifications(TestUser user) throws Exception {
        return mockMvc.perform(get("/api/v1/me/settings/notifications").with(user.auth()));
    }

    protected ResultActions putNotifications(TestUser user, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/me/settings/notifications").with(user.auth())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Switches one preference off, the rest at their defaults. */
    protected void switchOff(TestUser user, String field) throws Exception {
        String json = "{\"friendRequests\": true, \"groupInvites\": true, \"sessionStarted\": true, "
                + "\"comments\": true, \"weeklyDigest\": false}";
        putNotifications(user, json.replace("\"" + field + "\": true", "\"" + field + "\": false"))
                .andExpect(status().isOk());
    }

    protected void optInToDigest(TestUser user) throws Exception {
        putNotifications(user, "{\"friendRequests\": true, \"groupInvites\": true, \"sessionStarted\": true, "
                + "\"comments\": true, \"weeklyDigest\": true}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------- emails

    /** Every email sent since the last {@link #clearEmails()}, in order. */
    protected List<EmailMessage> sentEmails() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailGateway, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    protected List<EmailMessage> emailsTo(TestUser user) {
        return sentEmails().stream().filter(m -> m.to().equals(user.email())).toList();
    }

    protected void clearEmails() {
        Mockito.clearInvocations(emailGateway);
    }

    protected int rows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
