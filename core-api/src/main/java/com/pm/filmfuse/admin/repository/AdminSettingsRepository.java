package com.pm.filmfuse.admin.repository;

import com.pm.filmfuse.admin.dto.AdminUserRefDTO;
import com.pm.filmfuse.common.json.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.admin_settings}: stored overrides only (no row = the configured default applies). */
@Repository
public class AdminSettingsRepository {

    /** A stored override; {@code value} is the parsed JSON (Integer/Long, Boolean, ...). */
    public record StoredSetting(String key, Object value, AdminUserRefDTO updatedBy, Instant updatedAt) {
    }

    private static final String SELECT = """
            select s.key, s.value::text as value, s.updated_at, s.updated_by, p.username, p.display_name
            from app.admin_settings s
            left join app.profiles p on p.id = s.updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, StoredSetting> findAll() {
        Map<String, StoredSetting> byKey = new LinkedHashMap<>();
        jdbc.query(SELECT, rs -> {
            StoredSetting setting = map(rs);
            byKey.put(setting.key(), setting);
        });
        return byKey;
    }

    public Optional<StoredSetting> find(String key) {
        return jdbc.query(SELECT + " where s.key = :key", new MapSqlParameterSource("key", key),
                (rs, row) -> map(rs)).stream().findFirst();
    }

    /** The stored JSON value, locking the row for the rest of the transaction; empty when none. */
    public Optional<Object> lockValue(String key) {
        return jdbc.query("select value::text from app.admin_settings where key = :key for update",
                new MapSqlParameterSource("key", key),
                (rs, row) -> JsonColumns.read(rs.getString(1), Object.class)).stream().findFirst();
    }

    public void upsert(String key, Object value, UUID adminId, Instant now) {
        jdbc.update("""
                insert into app.admin_settings (key, value, updated_by, updated_at)
                values (:key, cast(:value as jsonb), :admin, :now)
                on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by,
                                                updated_at = excluded.updated_at
                """, new MapSqlParameterSource("key", key)
                .addValue("value", JsonColumns.write(value))
                .addValue("admin", adminId)
                .addValue("now", Timestamp.from(now)));
    }

    public boolean delete(String key) {
        return jdbc.update("delete from app.admin_settings where key = :key", new MapSqlParameterSource("key", key)) > 0;
    }

    private static StoredSetting map(ResultSet rs) throws SQLException {
        UUID updatedBy = rs.getObject("updated_by", UUID.class);
        return new StoredSetting(rs.getString("key"), JsonColumns.read(rs.getString("value"), Object.class),
                updatedBy == null ? null
                        : new AdminUserRefDTO(updatedBy, rs.getString("username"), rs.getString("display_name")),
                rs.getTimestamp("updated_at").toInstant());
    }
}
