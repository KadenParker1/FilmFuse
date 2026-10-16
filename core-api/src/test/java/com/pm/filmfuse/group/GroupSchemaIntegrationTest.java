package com.pm.filmfuse.group;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V6-V8: ml_reader access, constraints, cascades and indexes. */
class GroupSchemaIntegrationTest extends AbstractGroupIntegrationTest {

    private static final List<String> NEW_TABLES = List.of("app.movie_content_levels", "app.user_settings", "app.groups",
            "app.group_members", "app.rec_sessions", "app.rec_session_members", "app.rec_session_results",
            "app.rec_session_votes", "app.watch_history");

    @Test
    void mlReaderCanReadButNotWriteTheNewTables() {
        for (String table : NEW_TABLES) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isTrue();
            for (String write : new String[] {"INSERT", "UPDATE", "DELETE"}) {
                assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, ?)", Boolean.class, table, write))
                        .as("%s on %s", write, table).isFalse();
            }
        }
        assertThat(jdbc.queryForObject("select has_column_privilege('ml_reader', 'app.movies', 'certification', 'SELECT')",
                Boolean.class)).isTrue();
    }

    @Test
    void moviesHaveACheckedCertificationAndKeywords() {
        int genre = uniqueGenre();
        int id = movie(genre, "PG-13", 1);
        assertThat(jdbc.queryForObject("select certification from app.movies where id = ?", String.class, id))
                .isEqualTo("PG-13");
        assertThat(jdbc.queryForObject("select cardinality(keywords) from app.movies where id = ?", Integer.class, id))
                .isZero();
        assertThatThrownBy(() -> jdbc.update("update app.movies set certification = 'PG13' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app.movies set certification = 'NR' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contentLevelsAreCheckedAndGoWithTheMovie() {
        int id = movie(uniqueGenre(), "R", 1);
        jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source, confidence) "
                + "values (?, 'violence', 'strong', 'keywords', 0.9)", id);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) "
                + "values (?, 'gore', 'strong', 'keywords')", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) "
                + "values (?, 'sexual', 'extreme', 'keywords')", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) "
                + "values (?, 'sexual', 'none', 'guess')", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source, confidence) "
                + "values (?, 'sexual', 'none', 'manual', 1.5)", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) "
                + "values (?, 'violence', 'none', 'manual')", id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.movie_content_levels (movie_id, dimension, level, source) "
                + "values (-12345, 'violence', 'none', 'manual')")).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("delete from app.movies where id = ?", id);
        assertThat(jdbc.queryForObject("select count(*) from app.movie_content_levels where movie_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void userSettingsAreChecked() throws Exception {
        TestUser u = user();
        assertThatThrownBy(() -> jdbc.update("insert into app.user_settings (user_id, max_certification) values (?, 'PG13')",
                u.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.user_settings (user_id, content_limits) values (?, '[]')",
                u.id())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into app.user_settings (user_id) values (?)", u.id());
        assertThat(jdbc.queryForObject("select allow_unrated from app.user_settings where user_id = ?", Boolean.class, u.id()))
                .isFalse();
        assertThat(jdbc.queryForObject("select content_limits::text from app.user_settings where user_id = ?", String.class,
                u.id())).isEqualTo("{}");
    }

    @Test
    void groupConstraints() throws Exception {
        TestUser owner = user();
        TestUser other = user();
        UUID groupId = activeGroup(owner, other);

        assertThatThrownBy(() -> jdbc.update("update app.groups set color = 'red' where id = ?", groupId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app.groups set name = '' where id = ?", groupId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // One owner per group; an owner must be ACTIVE; ACTIVE members have joined_at.
        assertThatThrownBy(() -> jdbc.update("update app.group_members set role = 'OWNER' where group_id = ? and user_id = ?",
                groupId, other.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app.group_members set status = 'INVITED', joined_at = null "
                + "where group_id = ? and user_id = ?", groupId, owner.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app.group_members set joined_at = null where group_id = ? and user_id = ?",
                groupId, other.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAProfileRemovesTheirMembershipsVotesAndHistoryButKeepsSessions() throws Exception {
        TestUser leaver = user();
        TestUser stayer = user();
        UUID groupId = activeGroup(stayer, leaver);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createGroupSession(leaver, groupId, genre));
        vote(leaver, sessionId, movie, "UP").andExpect(status().isOk());
        pick(stayer, sessionId, movie).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me").with(leaver.auth())).andExpect(status().isNoContent());

        assertThat(count("select count(*) from app.group_members where user_id = ?", leaver.id())).isZero();
        assertThat(count("select count(*) from app.rec_session_members where user_id = ?", leaver.id())).isZero();
        assertThat(count("select count(*) from app.rec_session_votes where user_id = ?", leaver.id())).isZero();
        assertThat(count("select count(*) from app.watch_history where user_id = ?", leaver.id())).isZero();
        assertThat(count("select count(*) from app.watch_history where user_id = ?", stayer.id())).isEqualTo(1);
        assertThat(count("select count(*) from app.rec_sessions where id = ? and created_by is null", sessionId)).isEqualTo(1);
        assertThat(count("select count(*) from app.rec_session_results where session_id = ?", sessionId)).isEqualTo(1);
        assertThat(count("select count(*) from app.groups where id = ?", groupId)).isEqualTo(1);
    }

    @Test
    void deletingASessionCascadesToItsRowsButKeepsWatchHistory() throws Exception {
        TestUser a = user();
        TestUser b = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));
        vote(a, sessionId, movie, "UP").andExpect(status().isOk());
        pick(b, sessionId, movie).andExpect(status().isOk());

        jdbc.update("delete from app.rec_sessions where id = ?", sessionId);

        assertThat(count("select count(*) from app.rec_session_members where session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from app.rec_session_results where session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from app.rec_session_votes where session_id = ?", sessionId)).isZero();
        assertThat(count("select count(*) from app.watch_history where user_id = ? and movie_id = ? and session_id is null",
                a.id(), movie)).isEqualTo(1);
    }

    @Test
    void votesMustReferenceAMemberAndAResult() throws Exception {
        TestUser a = user();
        TestUser b = user();
        TestUser outsider = user();
        befriend(a, b);
        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 1);
        UUID sessionId = sessionId(createAdHocSession(a, genre, b));

        assertThatThrownBy(() -> jdbc.update("insert into app.rec_session_votes (session_id, user_id, movie_id, vote) "
                + "values (?, ?, ?, 'UP')", sessionId, outsider.id(), movie)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.rec_session_votes (session_id, user_id, movie_id, vote) "
                + "values (?, ?, -1, 'UP')", sessionId, a.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.rec_session_votes (session_id, user_id, movie_id, vote) "
                + "values (?, ?, ?, 'MEH')", sessionId, a.id(), movie)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app.rec_sessions set status = 'DECIDED' where id = ?", sessionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lookupIndexesExist() {
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'app'", String.class))
                .contains("movies_certification_popularity_idx", "groups_owner_idx", "group_members_one_owner_idx",
                        "group_members_user_idx", "rec_sessions_group_idx", "rec_session_members_user_idx",
                        "rec_session_votes_movie_idx", "watch_history_user_idx", "watch_history_session_idx");
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
