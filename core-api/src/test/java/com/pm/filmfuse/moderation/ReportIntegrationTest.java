package com.pm.filmfuse.moderation;

import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /reports (contract section D): validation, existence/visibility, self-reports, idempotency. */
@ExtendWith(OutputCaptureExtension.class)
class ReportIntegrationTest extends AbstractPhase5IntegrationTest {

    private int reports(TestUser reporter) {
        return rows("select count(*) from app.reports where reporter_id = ?", reporter.id());
    }

    private Map<String, Object> onlyReport(TestUser reporter) {
        return jdbc.queryForMap("select * from app.reports where reporter_id = ?", reporter.id());
    }

    @Test
    void reportingARatingStoresAnOpenReportAndLogsIdsOnly(CapturedOutput output) throws Exception {
        TestUser author = user();
        TestUser reporter = user();
        String ratingId = review(author, newMovie(), "PUBLIC");

        report(reporter, """
                {"targetType": "RATING", "targetId": "%s", "reason": "SPOILERS", "details": "  ruins the ending  "}
                """.formatted(ratingId)).andExpect(status().isNoContent());

        Map<String, Object> row = onlyReport(reporter);
        assertThat(row.get("target_type")).isEqualTo("RATING");
        assertThat(row.get("target_id").toString()).isEqualTo(ratingId);
        assertThat(row.get("reason")).isEqualTo("SPOILERS");
        assertThat(row.get("details")).isEqualTo("ruins the ending");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(output.getOut()).contains("filed by " + reporter.id() + " on RATING " + ratingId)
                .doesNotContain(reporter.email()).doesNotContain("ruins the ending");
    }

    @Test
    void commentsAndUsersCanBeReported() throws Exception {
        TestUser author = user();
        TestUser commenter = user();
        TestUser reporter = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        String commentId = commentId(commenter, ratingId, "spam spam");

        report(reporter, "COMMENT", commentId, "SPAM").andExpect(status().isNoContent());
        report(reporter, "USER", commenter.id(), "HARASSMENT").andExpect(status().isNoContent());
        assertThat(reports(reporter)).isEqualTo(2);
        assertThat(jdbc.queryForList("select target_type from app.reports where reporter_id = ? order by target_type",
                String.class, reporter.id())).containsExactly("COMMENT", "USER");
    }

    @Test
    void oneOpenReportPerReporterAndTarget() throws Exception {
        TestUser author = user();
        TestUser reporter = user();
        TestUser another = user();
        String ratingId = review(author, newMovie(), "PUBLIC");

        report(reporter, "RATING", ratingId, "SPAM").andExpect(status().isNoContent());
        report(reporter, "RATING", ratingId, "HATE").andExpect(status().isNoContent());
        assertThat(reports(reporter)).isEqualTo(1);
        assertThat(onlyReport(reporter).get("reason")).isEqualTo("SPAM");
        report(another, "RATING", ratingId, "SPAM").andExpect(status().isNoContent());
        assertThat(reports(another)).isEqualTo(1);

        // Once handled, the same person may report it again.
        jdbc.update("update app.reports set status = 'RESOLVED' where reporter_id = ?", reporter.id());
        report(reporter, "RATING", ratingId, "HATE").andExpect(status().isNoContent());
        assertThat(reports(reporter)).isEqualTo(2);
        // The same id as a different target type is a different target.
        report(reporter, "USER", author.id(), "SPAM").andExpect(status().isNoContent());
        assertThat(reports(reporter)).isEqualTo(3);
    }

    @Test
    void validation() throws Exception {
        TestUser reporter = user();
        UUID someone = user().id();

        report(reporter, "{\"targetId\": \"%s\", \"reason\": \"SPAM\"}".formatted(someone))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.targetType").value("Required"));
        report(reporter, "{\"targetType\": \"USER\", \"reason\": \"SPAM\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.targetId").value("Required"));
        report(reporter, "{\"targetType\": \"USER\", \"targetId\": \"%s\"}".formatted(someone))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").value("Required"));
        report(reporter, "USER", "not-a-uuid", "SPAM")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.targetId").value("Must be a valid id"));
        report(reporter, "POST", someone, "SPAM").andExpect(status().isBadRequest());
        report(reporter, "USER", someone, "RUDE").andExpect(status().isBadRequest());
        report(reporter, "{\"targetType\": \"USER\", \"targetId\": \"%s\", \"reason\": \"OTHER\", \"details\": \"%s\"}"
                .formatted(someone, "d".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.details").value("Details cannot exceed 500 characters"));
        report(reporter, "{nope").andExpect(status().isBadRequest());
        assertThat(reports(reporter)).isZero();

        report(reporter, "{\"targetType\": \"USER\", \"targetId\": \"%s\", \"reason\": \"OTHER\", \"details\": \"   \"}"
                .formatted(someone)).andExpect(status().isNoContent());
        assertThat(onlyReport(reporter).get("details")).isNull();
    }

    @Test
    void everyReasonIsAccepted() throws Exception {
        TestUser reporter = user();
        for (String reason : new String[] {"SPAM", "HARASSMENT", "HATE", "SEXUAL_CONTENT", "SPOILERS", "OTHER"}) {
            report(reporter, "USER", user().id(), reason).andExpect(status().isNoContent());
        }
        assertThat(reports(reporter)).isEqualTo(6);
    }

    @Test
    void unknownOrInvisibleTargetsAre404() throws Exception {
        TestUser author = user();
        TestUser reporter = user();
        TestUser commenter = user();
        TestUser friendOfAuthor = user();
        befriend(author, friendOfAuthor);
        String friendsOnly = review(author, newMovie(), "FRIENDS");
        String priv = review(author, newMovie(), "PRIVATE");
        String swipe = swipe(author, newMovie(), 3);
        String onFriendsOnly = commentId(friendOfAuthor, friendsOnly, "hidden from strangers");
        String pub = review(author, newMovie(), "PUBLIC");
        String byBlocked = commentId(commenter, pub, "from someone the reporter blocks");
        block(reporter, commenter);

        String random = UUID.randomUUID().toString();
        for (String[] target : new String[][] {
                {"RATING", random}, {"COMMENT", random}, {"USER", random},
                {"RATING", friendsOnly}, {"RATING", priv}, {"RATING", swipe},
                {"COMMENT", onFriendsOnly}, {"COMMENT", byBlocked}, {"COMMENT", pub}, {"RATING", byBlocked}}) {
            report(reporter, target[0], target[1], "SPAM").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("We couldn't find what you're reporting."));
        }
        assertThat(reports(reporter)).isZero();

        // A friend can see (and so report) the friends-only rating and its comment.
        report(friendOfAuthor, "RATING", friendsOnly, "SPAM").andExpect(status().isNoContent());
    }

    @Test
    void aRatingBySomeoneWhoBlockedTheReporterIs404ButTheUserCanStillBeReported() throws Exception {
        TestUser author = user();
        TestUser reporter = user();
        String pub = review(author, newMovie(), "PUBLIC");
        block(author, reporter);

        report(reporter, "RATING", pub, "HARASSMENT").andExpect(status().isNotFound());
        report(reporter, "USER", author.id(), "HARASSMENT").andExpect(status().isNoContent());
        TestUser suspended = user();
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", suspended.id());
        report(reporter, "USER", suspended.id(), "SPAM").andExpect(status().isNoContent());
    }

    @Test
    void selfReportsAre400() throws Exception {
        TestUser me = user();
        String mine = review(me, newMovie(), "PRIVATE");
        String myComment = commentId(me, review(user(), newMovie(), "PUBLIC"), "mine");

        for (String[] target : new String[][] {{"RATING", mine}, {"COMMENT", myComment}, {"USER", me.id().toString()}}) {
            report(me, target[0], target[1], "SPAM").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("You can't report yourself or your own posts."));
        }
        assertThat(reports(me)).isZero();
    }
}
