package com.pm.filmfuse.moderation.repository;

import com.pm.filmfuse.moderation.dto.ReportReason;
import com.pm.filmfuse.moderation.dto.ReportTargetType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.reports}. */
@Repository
public class ReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores an OPEN report unless the reporter already has an OPEN one on the same target.
     *
     * @return whether a new report was stored
     */
    public boolean insertIfNoOpen(UUID id, UUID reporterId, ReportTargetType targetType, UUID targetId,
                                  ReportReason reason, String details, Instant now) {
        return jdbc.update("""
                insert into app.reports (id, reporter_id, target_type, target_id, reason, details, status, created_at)
                values (:id, :reporter, :type, :target, :reason, :details, 'OPEN', :now)
                on conflict (reporter_id, target_type, target_id) where status = 'OPEN' do nothing
                """, new MapSqlParameterSource("id", id)
                .addValue("reporter", reporterId)
                .addValue("type", targetType.name())
                .addValue("target", targetId)
                .addValue("reason", reason.name())
                .addValue("details", details)
                .addValue("now", Timestamp.from(now))) > 0;
    }
}
