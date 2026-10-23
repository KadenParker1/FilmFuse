package com.pm.filmfuse.rating.repository;

import com.pm.filmfuse.rating.model.PendingRating;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Post-watch prompts: reads {@code app.watch_history} (written by session picks) against
 * {@code app.ratings} and owns {@code app.pending_rating_dismissals}.
 */
@Repository
public class PendingRatingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PendingRatingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Movies in {@code userId}'s session watch history since {@code since} that they have not rated
     * (any source) and not dismissed - one row per movie (its latest watch), newest first.
     */
    public List<PendingRating> pending(UUID userId, Instant since, int limit) {
        return jdbc.query("""
                select * from (
                    select distinct on (w.movie_id) w.movie_id, w.session_id, g.name as group_name, w.watched_at
                    from app.watch_history w
                    join app.rec_sessions s on s.id = w.session_id
                    left join app.groups g on g.id = s.group_id
                    where w.user_id = :user and w.watched_at >= :since
                      and not exists (select 1 from app.ratings r where r.user_id = :user and r.movie_id = w.movie_id)
                      and not exists (select 1 from app.pending_rating_dismissals d
                                      where d.user_id = :user and d.movie_id = w.movie_id)
                    order by w.movie_id, w.watched_at desc, w.id desc
                ) t
                order by watched_at desc, movie_id
                limit :limit
                """, new MapSqlParameterSource("user", userId).addValue("since", Timestamp.from(since))
                .addValue("limit", limit), (rs, n) -> new PendingRating(rs.getInt("movie_id"),
                rs.getObject("session_id", UUID.class), rs.getString("group_name"),
                rs.getTimestamp("watched_at").toInstant()));
    }

    /** Idempotent: a repeated dismissal keeps the first timestamp. */
    public void dismiss(UUID userId, int movieId, Instant now) {
        jdbc.update("""
                insert into app.pending_rating_dismissals (user_id, movie_id, created_at) values (:user, :movie, :now)
                on conflict (user_id, movie_id) do nothing
                """, new MapSqlParameterSource("user", userId).addValue("movie", movieId)
                .addValue("now", Timestamp.from(now)));
    }

    /** Undo of a dismissal; idempotent. */
    public void undismiss(UUID userId, int movieId) {
        jdbc.update("delete from app.pending_rating_dismissals where user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("user", userId).addValue("movie", movieId));
    }
}
