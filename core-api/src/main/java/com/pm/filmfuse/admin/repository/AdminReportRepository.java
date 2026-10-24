package com.pm.filmfuse.admin.repository;

import com.pm.filmfuse.admin.dto.AdminReportDTO;
import com.pm.filmfuse.admin.dto.AdminUserRefDTO;
import com.pm.filmfuse.admin.dto.ReportStatus;
import com.pm.filmfuse.admin.dto.ReportTargetPreviewDTO;
import com.pm.filmfuse.moderation.dto.ReportReason;
import com.pm.filmfuse.moderation.dto.ReportTargetType;
import com.pm.filmfuse.user.model.AccountStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The moderation queue over {@code app.reports}: one query per page joins the reporter, the target
 * (rating + movie, comment + its rating's movie, or user) and the open-report count per target - no
 * per-row lookups.
 */
@Repository
public class AdminReportRepository {

    static final int PREVIEW_CHARS = 500;

    /** The report row plus its target, resolved by type. Target ids have no FK, so every join is a left join. */
    private static final String SELECT = """
            select r.id, r.target_type, r.target_id, r.reason, r.details, r.status, r.created_at,
                   r.resolved_at, r.resolution_note,
                   r.reporter_id, rep.username as reporter_username, rep.display_name as reporter_display_name,
                   r.resolved_by, res.username as resolver_username, res.display_name as resolver_display_name,
                   rt.id as rating_id, rt.score, rt.body as rating_body,
                   c.id as comment_id, c.body as comment_body,
                   m.id as movie_id, m.title as movie_title,
                   author.id as author_id, author.username as author_username,
                   author.display_name as author_display_name, author.status as author_status, author.bio as author_bio,
                   (select count(*) from app.reports o
                    where o.target_type = r.target_type and o.target_id = r.target_id and o.status = 'OPEN')
                       as open_on_target
            from app.reports r
            left join app.profiles rep on rep.id = r.reporter_id
            left join app.profiles res on res.id = r.resolved_by
            left join app.rating_comments c on r.target_type = 'COMMENT' and c.id = r.target_id
            left join app.ratings rt on (r.target_type = 'RATING' and rt.id = r.target_id)
                                     or (r.target_type = 'COMMENT' and rt.id = c.rating_id)
            left join app.movies m on m.id = rt.movie_id
            left join app.profiles author on author.id = case r.target_type
                                                             when 'USER' then r.target_id
                                                             when 'COMMENT' then c.user_id
                                                             else rt.user_id end
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of reports in {@code status}: OPEN oldest first (a queue), closed ones newest first. The
     * cursor is the last row's ({@code created_at}, {@code id}); both null for the first page.
     */
    public List<AdminReportDTO> page(ReportStatus status, Instant afterCreatedAt, UUID afterId, int limit) {
        boolean ascending = status == ReportStatus.OPEN;
        String keyset = ascending ? "(r.created_at, r.id) > (cast(:at as timestamptz), cast(:id as uuid))"
                : "(r.created_at, r.id) < (cast(:at as timestamptz), cast(:id as uuid))";
        String order = ascending ? "r.created_at, r.id" : "r.created_at desc, r.id desc";
        return jdbc.query(SELECT + """
                where r.status = :status and (cast(:at as timestamptz) is null or %s)
                order by %s
                limit :limit
                """.formatted(keyset, order), new MapSqlParameterSource("status", status.name())
                .addValue("at", afterCreatedAt == null ? null : Timestamp.from(afterCreatedAt))
                .addValue("id", afterId)
                .addValue("limit", limit), (rs, row) -> map(rs));
    }

    public Optional<AdminReportDTO> find(UUID id) {
        return jdbc.query(SELECT + " where r.id = :id", new MapSqlParameterSource("id", id), (rs, row) -> map(rs))
                .stream().findFirst();
    }

    /** The status, locking the row; empty for an unknown id. */
    public Optional<ReportStatus> lockStatus(UUID id) {
        return jdbc.query("select status from app.reports where id = :id for update",
                new MapSqlParameterSource("id", id), (rs, row) -> ReportStatus.valueOf(rs.getString(1)))
                .stream().findFirst();
    }

    public void close(UUID id, ReportStatus status, UUID adminId, String note, Instant now) {
        jdbc.update("""
                update app.reports set status = :status, resolved_at = :now, resolved_by = :admin,
                                       resolution_note = :note
                where id = :id and status = 'OPEN'
                """, new MapSqlParameterSource("id", id)
                .addValue("status", status.name())
                .addValue("now", Timestamp.from(now))
                .addValue("admin", adminId)
                .addValue("note", note));
    }

    private static AdminReportDTO map(ResultSet rs) throws SQLException {
        ReportTargetType type = ReportTargetType.valueOf(rs.getString("target_type"));
        UUID authorId = rs.getObject("author_id", UUID.class);
        boolean exists = switch (type) {
            case RATING -> rs.getObject("rating_id") != null;
            case COMMENT -> rs.getObject("comment_id") != null;
            case USER -> authorId != null;
        };
        String text = switch (type) {
            case RATING -> rs.getString("rating_body");
            case COMMENT -> rs.getString("comment_body");
            case USER -> rs.getString("author_bio");
        };
        Integer movieId = (Integer) rs.getObject("movie_id");
        Integer score = rs.getObject("score") == null ? null : rs.getInt("score");
        String authorStatus = rs.getString("author_status");
        ReportTargetPreviewDTO target = new ReportTargetPreviewDTO(
                exists,
                authorId == null ? null : new AdminUserRefDTO(authorId, rs.getString("author_username"),
                        rs.getString("author_display_name")),
                truncate(text),
                movieId,
                rs.getString("movie_title"),
                type == ReportTargetType.RATING ? score : null,
                authorStatus == null ? null : AccountStatus.valueOf(authorStatus));
        UUID resolverId = rs.getObject("resolved_by", UUID.class);
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new AdminReportDTO(
                rs.getObject("id", UUID.class),
                type,
                rs.getObject("target_id", UUID.class),
                ReportReason.valueOf(rs.getString("reason")),
                rs.getString("details"),
                ReportStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                new AdminUserRefDTO(rs.getObject("reporter_id", UUID.class), rs.getString("reporter_username"),
                        rs.getString("reporter_display_name")),
                target,
                rs.getInt("open_on_target"),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                resolverId == null ? null : new AdminUserRefDTO(resolverId, rs.getString("resolver_username"),
                        rs.getString("resolver_display_name")),
                rs.getString("resolution_note"));
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= PREVIEW_CHARS) {
            return text;
        }
        return text.substring(0, PREVIEW_CHARS - 1) + "…";
    }
}
