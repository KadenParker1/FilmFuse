package com.pm.filmfuse.user.repository;

import com.pm.filmfuse.common.json.JsonColumns;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.model.ContentFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app.user_settings}. Phase 3 stores only the content comfort fields. A user without a row
 * has {@link ContentFilter#USER_DEFAULT}.
 */
@Repository
public class UserSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Comfort settings of each of {@code userIds}; users without a row get the default. */
    public Map<UUID, ContentFilter> findComfort(Collection<UUID> userIds) {
        Map<UUID, ContentFilter> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select user_id, max_certification, content_limits::text as content_limits, allow_unrated
                from app.user_settings where user_id in (:ids)
                """, new MapSqlParameterSource("ids", userIds), rs -> {
            result.put(rs.getObject("user_id", UUID.class), new ContentFilter(
                    Certification.fromLabel(rs.getString("max_certification")),
                    ContentFilter.limitsFromJson(rs.getString("content_limits")),
                    rs.getBoolean("allow_unrated")));
        });
        userIds.forEach(id -> result.putIfAbsent(id, ContentFilter.USER_DEFAULT));
        return result;
    }

    public void upsertComfort(UUID userId, ContentFilter comfort, Instant now) {
        jdbc.update("""
                insert into app.user_settings (user_id, max_certification, content_limits, allow_unrated, updated_at)
                values (:id, :max, cast(:limits as jsonb), :allowUnrated, :now)
                on conflict (user_id) do update set
                    max_certification = excluded.max_certification,
                    content_limits    = excluded.content_limits,
                    allow_unrated     = excluded.allow_unrated,
                    updated_at        = excluded.updated_at
                """, new MapSqlParameterSource("id", userId)
                .addValue("max", comfort.maxCertification() == null ? null : comfort.maxCertification().label())
                .addValue("limits", JsonColumns.write(comfort.contentLimitCodes()))
                .addValue("allowUnrated", comfort.allowUnrated())
                .addValue("now", Timestamp.from(now)));
    }
}
