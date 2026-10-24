package com.pm.filmfuse.admin.repository;

import com.pm.filmfuse.admin.dto.AdminAction;
import com.pm.filmfuse.admin.dto.AdminAuditEntryDTO;
import com.pm.filmfuse.admin.dto.AdminUserRefDTO;
import com.pm.filmfuse.common.json.JsonColumns;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.admin_audit_log}: insert and a newest-first keyset list. */
@Repository
public class AdminAuditRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID adminId, AdminAction action, String target, Object oldValue, Object newValue,
                       Instant now) {
        jdbc.update("""
                insert into app.admin_audit_log (id, admin_id, action, target, old_value, new_value, created_at)
                values (:id, :admin, :action, :target, cast(:old as jsonb), cast(:new as jsonb), :now)
                """, new MapSqlParameterSource("id", id)
                .addValue("admin", adminId)
                .addValue("action", action.name())
                .addValue("target", target)
                .addValue("old", oldValue == null ? null : JsonColumns.write(oldValue))
                .addValue("new", newValue == null ? null : JsonColumns.write(newValue))
                .addValue("now", Timestamp.from(now)));
    }

    /**
     * Up to {@code limit} entries older than the cursor ({@code beforeCreatedAt}, {@code beforeId}; both
     * null for the first page), newest first, optionally of one action.
     */
    public List<AdminAuditEntryDTO> page(AdminAction action, Instant beforeCreatedAt, UUID beforeId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit)
                .addValue("action", action == null ? null : action.name())
                .addValue("beforeAt", beforeCreatedAt == null ? null : Timestamp.from(beforeCreatedAt))
                .addValue("beforeId", beforeId);
        return jdbc.query("""
                select a.id, a.admin_id, p.username, p.display_name, a.action, a.target,
                       a.old_value::text as old_value, a.new_value::text as new_value, a.created_at
                from app.admin_audit_log a
                left join app.profiles p on p.id = a.admin_id
                where (cast(:action as text) is null or a.action = cast(:action as text))
                  and (cast(:beforeAt as timestamptz) is null
                       or (a.created_at, a.id) < (cast(:beforeAt as timestamptz), cast(:beforeId as uuid)))
                order by a.created_at desc, a.id desc
                limit :limit
                """, params, (rs, row) -> {
            UUID adminId = rs.getObject("admin_id", UUID.class);
            String oldValue = rs.getString("old_value");
            String newValue = rs.getString("new_value");
            return new AdminAuditEntryDTO(
                    rs.getObject("id", UUID.class),
                    adminId == null ? null
                            : new AdminUserRefDTO(adminId, rs.getString("username"), rs.getString("display_name")),
                    AdminAction.valueOf(rs.getString("action")),
                    rs.getString("target"),
                    oldValue == null ? null : JsonColumns.read(oldValue, Object.class),
                    newValue == null ? null : JsonColumns.read(newValue, Object.class),
                    rs.getTimestamp("created_at").toInstant());
        });
    }
}
