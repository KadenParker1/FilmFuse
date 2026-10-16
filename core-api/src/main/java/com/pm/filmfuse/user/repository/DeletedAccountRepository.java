package com.pm.filmfuse.user.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.deleted_accounts}: ids of deleted accounts, checked on every authenticated request. */
@Repository
public class DeletedAccountRepository {

    private final JdbcTemplate jdbc;

    public DeletedAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isDeleted(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from app.deleted_accounts where id = ?)", Boolean.class, userId));
    }

    /** Idempotent: deleting twice keeps the first timestamp. */
    public void markDeleted(UUID userId) {
        jdbc.update("insert into app.deleted_accounts (id) values (?) on conflict (id) do nothing", userId);
    }
}
