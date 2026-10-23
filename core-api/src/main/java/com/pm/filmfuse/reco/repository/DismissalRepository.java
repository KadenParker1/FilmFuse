package com.pm.filmfuse.reco.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.rec_dismissals} ("Not interested"). */
@Repository
public class DismissalRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DismissalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return whether the dismissal is new */
    public boolean insert(UUID userId, int movieId, Instant now) {
        return jdbc.update("""
                insert into app.rec_dismissals (user_id, movie_id, created_at) values (:user, :movie, :now)
                on conflict do nothing
                """, new MapSqlParameterSource("user", userId).addValue("movie", movieId)
                .addValue("now", Timestamp.from(now))) > 0;
    }

    /** Undo of "Not interested". @return whether a dismissal was removed */
    public boolean delete(UUID userId, int movieId) {
        return jdbc.update("delete from app.rec_dismissals where user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("user", userId).addValue("movie", movieId)) > 0;
    }
}
