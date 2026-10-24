package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The moderation queue: previews per target type, paging, resolve / dismiss with a note, audit, 404 / 409. */
class AdminReportsIntegrationTest extends AbstractAdminIntegrationTest {

    private static final AtomicInteger NEXT_MOVIE = new AtomicInteger(910_000);

    private UUID admin;
    private Instant base;

    @BeforeEach
    void cleanQueue() {
        jdbc.update("delete from app.reports");
        admin = newAdmin();
        base = Instant.parse("2026-01-01T00:00:00Z");
    }

    private int movie(String title) {
        int id = NEXT_MOVIE.incrementAndGet();
        jdbc.update("insert into app.movies (id, title, cached_at) values (?, ?, now())", id, title);
        return id;
    }

    private UUID rating(UUID author, int movieId, int score, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into app.ratings (id, user_id, movie_id, score, source, body, visibility)
                values (?, ?, ?, ?, 'post_watch', ?, 'PUBLIC')
                """, id, author, movieId, score, body);
        return id;
    }

    private UUID comment(UUID author, UUID ratingId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app.rating_comments (id, rating_id, user_id, body) values (?, ?, ?, ?)", id, ratingId,
                author, body);
        return id;
    }

    /** An OPEN report, {@code minutes} after the test's base time. */
    private UUID report(UUID reporter, String type, UUID target, String reason, int minutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into app.reports (id, reporter_id, target_type, target_id, reason, details, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, reporter, type, target, reason, "details " + minutes,
                Timestamp.from(base.plusSeconds(minutes * 60L)));
        return id;
    }

    private ResultActions resolve(UUID reportId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void theQueueListsOpenReportsOldestFirstWithATargetPreview() throws Exception {
        UUID author = newUser();
        UUID commenter = newUser();
        UUID reporter = newUser();
        UUID other = newUser();
        jdbc.update("update app.profiles set bio = 'my bio' where id = ?", commenter);
        int movieId = movie("Heat");
        UUID ratingId = rating(author, movieId, 9, "Great heist");
        UUID commentId = comment(commenter, ratingId, "spam spam");

        UUID onRating = report(reporter, "RATING", ratingId, "SPOILERS", 1);
        UUID onComment = report(reporter, "COMMENT", commentId, "SPAM", 2);
        UUID onUser = report(reporter, "USER", commenter, "HARASSMENT", 3);
        report(other, "RATING", ratingId, "SPAM", 4);

        String json = mockMvc.perform(get("/api/v1/admin/reports").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").value(onRating.toString()))
                .andExpect(jsonPath("$.items[0].targetType").value("RATING"))
                .andExpect(jsonPath("$.items[0].reason").value("SPOILERS"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].details").value("details 1"))
                .andExpect(jsonPath("$.items[0].reporter.id").value(reporter.toString()))
                .andExpect(jsonPath("$.items[0].target.exists").value(true))
                .andExpect(jsonPath("$.items[0].target.author.id").value(author.toString()))
                .andExpect(jsonPath("$.items[0].target.text").value("Great heist"))
                .andExpect(jsonPath("$.items[0].target.movieId").value(movieId))
                .andExpect(jsonPath("$.items[0].target.movieTitle").value("Heat"))
                .andExpect(jsonPath("$.items[0].target.score").value(9))
                .andExpect(jsonPath("$.items[0].target.authorStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].openReportsOnTarget").value(2))
                .andExpect(jsonPath("$.items[1].id").value(onComment.toString()))
                .andExpect(jsonPath("$.items[1].target.author.id").value(commenter.toString()))
                .andExpect(jsonPath("$.items[1].target.text").value("spam spam"))
                .andExpect(jsonPath("$.items[1].target.movieTitle").value("Heat"))
                .andExpect(jsonPath("$.items[1].target.score").doesNotExist())
                .andExpect(jsonPath("$.items[1].openReportsOnTarget").value(1))
                .andExpect(jsonPath("$.items[2].id").value(onUser.toString()))
                .andExpect(jsonPath("$.items[2].target.author.id").value(commenter.toString()))
                .andExpect(jsonPath("$.items[2].target.text").value("my bio"))
                .andReturn().getResponse().getContentAsString();
        // No email ever leaves through the queue.
        assertThat(json).doesNotContain("@example.com");
    }

    @Test
    void aDeletedTargetStillListsWithExistsFalse() throws Exception {
        UUID reporter = newUser();
        UUID gone = UUID.randomUUID();
        report(reporter, "RATING", gone, "SPAM", 1);
        mockMvc.perform(get("/api/v1/admin/reports").with(as(admin)))
                .andExpect(jsonPath("$.items[0].target.exists").value(false))
                .andExpect(jsonPath("$.items[0].target.author").doesNotExist())
                .andExpect(jsonPath("$.items[0].target.text").doesNotExist());
    }

    @Test
    void pagesWithAnOpaqueCursor() throws Exception {
        UUID reporter = newUser();
        UUID first = report(reporter, "USER", newUser(), "SPAM", 1);
        UUID second = report(reporter, "USER", newUser(), "SPAM", 2);
        UUID third = report(reporter, "USER", newUser(), "SPAM", 3);

        String page1 = mockMvc.perform(get("/api/v1/admin/reports").param("limit", "2").with(as(admin)))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(first.toString(),
                        second.toString())))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        mockMvc.perform(get("/api/v1/admin/reports").param("limit", "2").param("cursor", cursor).with(as(admin)))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(third.toString())))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/reports").param("cursor", "garbage!").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/reports").param("limit", "0").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/reports").param("limit", "101").with(as(admin)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/reports").param("status", "BOGUS").with(as(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolvingClosesTheReportWithANoteAndAudits() throws Exception {
        UUID reporter = newUser();
        UUID reportId = report(reporter, "USER", newUser(), "HARASSMENT", 1);

        resolve(reportId, "{\"resolution\": \"RESOLVED\", \"note\": \"  Suspended the account  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Suspended the account"))
                .andExpect(jsonPath("$.resolvedBy.id").value(admin.toString()))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.openReportsOnTarget").value(0));

        Map<String, Object> row = jdbc.queryForMap("select status, resolved_by, resolution_note from app.reports where id = ?",
                reportId);
        assertThat(row).containsEntry("status", "RESOLVED").containsEntry("resolved_by", admin)
                .containsEntry("resolution_note", "Suspended the account");

        List<Map<String, Object>> audit = jdbc.queryForList("""
                select action, old_value::text as old_value, new_value::text as new_value from app.admin_audit_log
                where target = ? and admin_id = ?
                """, "report:" + reportId, admin);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("action", "REPORT_RESOLVE")
                .containsEntry("old_value", "{\"status\": \"OPEN\"}");
        assertThat((String) audit.getFirst().get("new_value")).contains("\"RESOLVED\"").contains("Suspended the account");

        // Gone from the open queue, listed under RESOLVED.
        mockMvc.perform(get("/api/v1/admin/reports").with(as(admin))).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/admin/reports").param("status", "RESOLVED").with(as(admin)))
                .andExpect(jsonPath("$.items[0].id").value(reportId.toString()));
        // The reporter may report the same target again (V13's one-OPEN rule).
        assertThat(jdbc.queryForObject("select count(*) from app.reports where reporter_id = ? and status = 'OPEN'",
                Long.class, reporter)).isZero();
    }

    @Test
    void dismissingNeedsNoNote() throws Exception {
        UUID reportId = report(newUser(), "USER", newUser(), "OTHER", 1);
        resolve(reportId, "{\"resolution\": \"DISMISSED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.resolutionNote").doesNotExist());
        assertThat(jdbc.queryForObject("select action from app.admin_audit_log where target = ?", String.class,
                "report:" + reportId)).isEqualTo("REPORT_DISMISS");
    }

    @Test
    void aHandledReportIs409AndAnUnknownOne404() throws Exception {
        UUID reportId = report(newUser(), "USER", newUser(), "SPAM", 1);
        resolve(reportId, "{\"resolution\": \"DISMISSED\"}").andExpect(status().isOk());
        resolve(reportId, "{\"resolution\": \"RESOLVED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This report has already been handled."));
        resolve(UUID.randomUUID(), "{\"resolution\": \"RESOLVED\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from app.admin_audit_log where target = ?", Long.class,
                "report:" + reportId)).isEqualTo(1);
    }

    @Test
    void validation() throws Exception {
        UUID reportId = report(newUser(), "USER", newUser(), "SPAM", 1);
        resolve(reportId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.resolution").exists());
        resolve(reportId, "{\"resolution\": \"OPEN\"}").andExpect(status().isBadRequest());
        resolve(reportId, "{\"resolution\": \"RESOLVED\", \"note\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        resolve(reportId, "{\"resolution\": \"RESOLVED\", \"note\": \"bad\\u202Etext\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.note").exists());
        assertThat(jdbc.queryForObject("select status from app.reports where id = ?", String.class, reportId))
                .isEqualTo("OPEN");
    }
}
