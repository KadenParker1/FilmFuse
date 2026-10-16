package com.pm.filmfuse.common;

import com.pm.filmfuse.support.AbstractIntegrationTest;
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
                "select has_table_privilege('ml_reader', 'app.profiles', 'SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "select has_table_privilege('ml_reader', 'app.profiles', 'INSERT')", Boolean.class)).isFalse();
    }

    @Test
    void usernameMustBeLowercase() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into app.profiles (id, username) values (?, 'NotLower')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
