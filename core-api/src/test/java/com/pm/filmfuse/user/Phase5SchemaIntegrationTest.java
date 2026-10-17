package com.pm.filmfuse.user;

import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 5 tables: ml_reader access, constraints, account-deletion cascades and 401s. */
class Phase5SchemaIntegrationTest extends AbstractPhase5IntegrationTest {

    private static final String[] TABLES = {
            "app.pending_rating_dismissals", "app.rating_comments", "app.notification_settings"};

    @Test
    void mlReaderCanReadButNotWriteTheNewTables() {
        for (String table : TABLES) {
            for (String privilege : new String[] {"INSERT", "UPDATE", "DELETE"}) {
                assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, ?)", Boolean.class, table,
                        privilege)).as("%s on %s", privilege, table).isFalse();
            }
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isTrue();
        }
        assertThat(jdbc.queryForObject("select has_column_privilege('ml_reader', 'app.movies', "
                + "'certification_checked_at', 'SELECT')", Boolean.class)).isTrue();
    }

    @Test
    void constraintsBackTheApiRules() throws Exception {
        TestUser author = user();
        String ratingId = review(author, newMovie(), "PUBLIC");
        assertThatThrownBy(() -> jdbc.update("insert into app.rating_comments (id, rating_id, user_id, body) "
                + "values (?, ?::uuid, ?, '')", UUID.randomUUID(), ratingId, author.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.rating_comments (id, rating_id, user_id, body) "
                + "values (?, ?::uuid, ?, ?)", UUID.randomUUID(), ratingId, author.id(), "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.reports (id, reporter_id, target_type, target_id, reason) "
                + "values (?, ?, 'MOVIE', ?, 'SPAM')", UUID.randomUUID(), author.id(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.reports (id, reporter_id, target_type, target_id, reason) "
                + "values (?, ?, 'USER', ?, 'RUDE')", UUID.randomUUID(), author.id(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID target = UUID.randomUUID();
        jdbc.update("insert into app.reports (id, reporter_id, target_type, target_id, reason) values (?, ?, 'USER', ?, 'SPAM')",
                UUID.randomUUID(), author.id(), target);
        assertThatThrownBy(() -> jdbc.update("insert into app.reports (id, reporter_id, target_type, target_id, reason) "
                + "values (?, ?, 'USER', ?, 'HATE')", UUID.randomUUID(), author.id(), target))
                .as("one OPEN report per target").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnAccountRemovesItsPhase5RowsAndNobodyElses() throws Exception {
        TestUser leaving = user();
        TestUser staying = user();
        befriend(leaving, staying);
        Watched watched = watchAdHoc(leaving, staying);
        dismissPending(leaving, watched.movieId()).andExpect(status().isNoContent());
        dismissPending(staying, watched.movieId()).andExpect(status().isNoContent());
        String leavingRating = review(leaving, newMovie(), "PUBLIC");
        String stayingRating = review(staying, newMovie(), "PUBLIC");
        String onLeavingByStaying = commentId(staying, leavingRating, "on the leaver's rating");
        commentId(leaving, stayingRating, "by the leaver");
        String stayingOwn = commentId(staying, stayingRating, "kept");
        report(leaving, "RATING", stayingRating, "SPAM").andExpect(status().isNoContent());
        report(staying, "USER", leaving.id(), "SPAM").andExpect(status().isNoContent());
        optInToDigest(leaving);
        optInToDigest(staying);

        mockMvc.perform(delete("/api/v1/me").with(leaving.auth())).andExpect(status().isNoContent());

        assertThat(rows("select count(*) from app.pending_rating_dismissals where user_id = ?", leaving.id())).isZero();
        assertThat(rows("select count(*) from app.pending_rating_dismissals where user_id = ?", staying.id())).isEqualTo(1);
        assertThat(rows("select count(*) from app.rating_comments where user_id = ?", leaving.id())).isZero();
        assertThat(rows("select count(*) from app.rating_comments where id = ?::uuid", onLeavingByStaying))
                .as("comments on the deleted user's ratings go with the ratings").isZero();
        assertThat(rows("select count(*) from app.rating_comments where id = ?::uuid", stayingOwn)).isEqualTo(1);
        assertThat(rows("select count(*) from app.reports where reporter_id = ?", leaving.id())).isZero();
        assertThat(rows("select count(*) from app.reports where reporter_id = ? and target_id = ?", staying.id(),
                leaving.id())).as("a report about the deleted user is kept").isEqualTo(1);
        assertThat(rows("select count(*) from app.notification_settings where user_id = ?", leaving.id())).isZero();
        assertThat(rows("select count(*) from app.notification_settings where user_id = ?", staying.id())).isEqualTo(1);
    }

    @Test
    void everyNewEndpointNeedsASignedInCaller() throws Exception {
        UUID id = UUID.randomUUID();
        var requests = new org.springframework.test.web.servlet.RequestBuilder[] {
                get("/api/v1/me/pending-ratings"),
                post("/api/v1/me/pending-ratings/1/dismiss"),
                get("/api/v1/ratings/{id}", id),
                get("/api/v1/ratings/{id}/comments", id),
                post("/api/v1/ratings/{id}/comments", id).contentType("application/json").content("{\"body\": \"x\"}"),
                delete("/api/v1/comments/{id}", id),
                post("/api/v1/reports").contentType("application/json")
                        .content("{\"targetType\": \"USER\", \"targetId\": \"" + id + "\", \"reason\": \"SPAM\"}"),
                get("/api/v1/me/settings/notifications"),
                put("/api/v1/me/settings/notifications").contentType("application/json").content("{}")};
        for (var request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }
}
