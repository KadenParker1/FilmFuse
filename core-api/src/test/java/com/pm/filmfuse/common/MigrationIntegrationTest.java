package com.pm.filmfuse.common;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.user.model.AccountStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mlReaderRoleCanOnlyReadAppTables() {
        assertThat(jdbc.queryForObject(
                "select rolcanlogin from pg_roles where rolname = 'ml_reader'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "select has_column_privilege('ml_reader', 'app.profiles', 'id', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('ml_reader', 'app.profiles', 'INSERT')", Boolean.class)).isFalse();
    }

    /** V16: no email addresses, invite token hashes or moderation reports for the ML service. */
    @Test
    void mlReaderCannotReadPersonalOrModerationData() {
        assertThat(jdbc.queryForObject(
                "select has_column_privilege('ml_reader', 'app.profiles', 'email', 'SELECT')", Boolean.class)).isFalse();
        for (String column : new String[] {"id", "username", "display_name", "status", "created_at"}) {
            assertThat(jdbc.queryForObject("select has_column_privilege('ml_reader', 'app.profiles', ?, 'SELECT')",
                    Boolean.class, column)).as("SELECT on profiles.%s", column).isTrue();
        }
        for (String table : new String[] {"app.invites", "app.reports"}) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isFalse();
        }
    }

    /** V21: the admin role, runtime settings and the audit log are no recommendation signal. */
    @Test
    void mlReaderCannotReadAdminData() {
        assertThat(jdbc.queryForObject(
                "select has_column_privilege('ml_reader', 'app.profiles', 'role', 'SELECT')", Boolean.class)).isFalse();
        for (String table : new String[] {"app.admin_settings", "app.admin_audit_log"}) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isFalse();
        }
    }

    /** V21: everyone starts as a plain user. */
    @Test
    void profilesDefaultToTheUserRole() {
        assertThat(jdbc.queryForObject("""
                select column_default from information_schema.columns
                where table_schema = 'app' and table_name = 'profiles' and column_name = 'role'
                """, String.class)).startsWith("'user'");
    }

    /** V17: the never-enforced PENDING_VERIFICATION status is gone; ACTIVE and SUSPENDED remain. */
    @Test
    void statusIsOnlyActiveOrSuspended() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.profiles (id, status) values (?, 'PENDING_VERIFICATION')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (AccountStatus status : AccountStatus.values()) {
            UUID id = UUID.randomUUID();
            jdbc.update("insert into app.profiles (id, status) values (?, ?)", id, status.name());
            jdbc.update("delete from app.profiles where id = ?", id);
        }
        assertThat(AccountStatus.values()).extracting(Enum::name)
                .containsExactly("ACTIVE", "SUSPENDED");
    }

    @Test
    void usernameMustBeLowercase() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.profiles (id, username) values (?, 'NotLower')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void genresAreSeededWithTheNineteenTmdbGenres() {
        assertThat(jdbc.queryForObject("select count(*) from app.genres", Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForObject("select name from app.genres where id = 878", String.class))
                .isEqualTo("Science Fiction");
        assertThat(jdbc.queryForObject("select name from app.genres where id = 10770", String.class))
                .isEqualTo("TV Movie");
        assertThat(jdbc.queryForList("select id from app.genres", Integer.class)).containsExactlyInAnyOrder(
                28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37);
    }

    @Test
    void mlReaderCanReadButNotWriteTheCatalog() {
        for (String table : new String[] {"app.movies", "app.genres", "app.deleted_accounts"}) {
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'SELECT')", Boolean.class, table))
                    .as("SELECT on %s", table).isTrue();
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'INSERT')", Boolean.class, table))
                    .as("INSERT on %s", table).isFalse();
            assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', ?, 'UPDATE')", Boolean.class, table))
                    .as("UPDATE on %s", table).isFalse();
        }
    }

    @Test
    void moviesHaveAPopularityIndexAndRequireCachedAt() {
        assertThat(jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'app' and indexname = 'movies_popularity_idx'",
                String.class)).contains("popularity DESC");
        assertThatThrownBy(() -> jdbc.update("insert into app.movies (id, title) values (-1, 'No cache stamp')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select column_default from information_schema.columns "
                + "where table_schema = 'app' and table_name = 'movies' and column_name = 'genre_ids'", String.class))
                .contains("{}");
    }
}
