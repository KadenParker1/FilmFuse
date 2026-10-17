package com.pm.filmfuse.user.repository;

import com.pm.filmfuse.user.dto.NotificationSettingsDTO;
import com.pm.filmfuse.user.model.NotificationType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.notification_settings}. A user without a row has {@link NotificationSettingsDTO#DEFAULTS}. */
@Repository
public class NotificationSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotificationSettingsDTO> find(UUID userId) {
        return jdbc.query("""
                select friend_requests, group_invites, session_started, comments, weekly_digest
                from app.notification_settings where user_id = :id
                """, new MapSqlParameterSource("id", userId), (rs, n) -> new NotificationSettingsDTO(
                rs.getBoolean("friend_requests"), rs.getBoolean("group_invites"), rs.getBoolean("session_started"),
                rs.getBoolean("comments"), rs.getBoolean("weekly_digest"))).stream().findFirst();
    }

    public void upsert(UUID userId, NotificationSettingsDTO settings, Instant now) {
        jdbc.update("""
                insert into app.notification_settings (user_id, friend_requests, group_invites, session_started,
                                                       comments, weekly_digest, updated_at)
                values (:id, :friendRequests, :groupInvites, :sessionStarted, :comments, :weeklyDigest, :now)
                on conflict (user_id) do update set
                    friend_requests = excluded.friend_requests,
                    group_invites   = excluded.group_invites,
                    session_started = excluded.session_started,
                    comments        = excluded.comments,
                    weekly_digest   = excluded.weekly_digest,
                    updated_at      = excluded.updated_at
                """, new MapSqlParameterSource("id", userId)
                .addValue("friendRequests", settings.friendRequests())
                .addValue("groupInvites", settings.groupInvites())
                .addValue("sessionStarted", settings.sessionStarted())
                .addValue("comments", settings.comments())
                .addValue("weeklyDigest", settings.weeklyDigest())
                .addValue("now", Timestamp.from(now)));
    }

    /** The ids among {@code userIds} that have {@code type} switched on (a missing row = the defaults). */
    public Set<UUID> allowing(Collection<UUID> userIds, NotificationType type) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        boolean byDefault = type != NotificationType.WEEKLY_DIGEST;
        return new HashSet<>(jdbc.queryForList("""
                select p.id from app.profiles p
                left join app.notification_settings s on s.user_id = p.id
                where p.id in (:ids) and coalesce(s.%s, :byDefault)
                """.formatted(type.column()),
                new MapSqlParameterSource("ids", userIds).addValue("byDefault", byDefault), UUID.class));
    }

    /** Active users who opted in to the weekly digest and have an email address, in id order. */
    public List<UUID> digestRecipients() {
        return jdbc.queryForList("""
                select p.id from app.notification_settings s
                join app.profiles p on p.id = s.user_id
                where s.weekly_digest and p.status <> 'SUSPENDED' and p.email is not null and p.email <> ''
                order by p.id
                """, new MapSqlParameterSource(), UUID.class);
    }
}
