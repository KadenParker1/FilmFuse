package com.pm.filmfuse.rating.repository;

import com.pm.filmfuse.rating.dto.RatingSource;
import com.pm.filmfuse.rating.dto.RatingVisibility;
import com.pm.filmfuse.rating.model.Rating;
import java.sql.Date;
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

/** {@code app.ratings} and {@code app.rating_unseen}. {@code source} is stored as its lowercase code. */
@Repository
public class RatingRepository {

    private static final String COLUMNS = """
            id, user_id, movie_id, score, body, contains_spoilers, visibility, source, watched_on, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RatingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or fully overwrite the caller's rating of the movie ({@code created_at} and {@code id} survive). */
    public Rating upsert(UUID userId, int movieId, int score, String body, boolean containsSpoilers,
                         RatingVisibility visibility, RatingSource source, java.time.LocalDate watchedOn, Instant now) {
        return jdbc.queryForObject("""
                insert into app.ratings (id, user_id, movie_id, score, body, contains_spoilers, visibility, source,
                                         watched_on, created_at, updated_at)
                values (:newId, :user, :movie, :score, :body, :spoilers, :visibility, :source, :watchedOn, :now, :now)
                on conflict (user_id, movie_id) do update set
                    score             = excluded.score,
                    body              = excluded.body,
                    contains_spoilers = excluded.contains_spoilers,
                    visibility        = excluded.visibility,
                    source            = excluded.source,
                    watched_on        = excluded.watched_on,
                    updated_at        = excluded.updated_at
                returning
                """ + COLUMNS, new MapSqlParameterSource("newId", UUID.randomUUID())
                .addValue("user", userId)
                .addValue("movie", movieId)
                .addValue("score", score)
                .addValue("body", body)
                .addValue("spoilers", containsSpoilers)
                .addValue("visibility", visibility.name())
                .addValue("source", source.code())
                .addValue("watchedOn", watchedOn == null ? null : Date.valueOf(watchedOn))
                .addValue("now", Timestamp.from(now)), (rs, n) -> map(rs));
    }

    /**
     * A calibration swipe: a new rating is PRIVATE / ONBOARDING_SWIPE; an existing rating only gets
     * the new score (its note, visibility and source are kept - a swipe never unpublishes a review).
     */
    public void upsertCalibration(UUID userId, int movieId, int score, Instant now) {
        jdbc.update("""
                insert into app.ratings (id, user_id, movie_id, score, visibility, source, created_at, updated_at)
                values (:newId, :user, :movie, :score, 'PRIVATE', :source, :now, :now)
                on conflict (user_id, movie_id) do update set score = excluded.score, updated_at = excluded.updated_at
                """, new MapSqlParameterSource("newId", UUID.randomUUID())
                .addValue("user", userId)
                .addValue("movie", movieId)
                .addValue("score", score)
                .addValue("source", RatingSource.ONBOARDING_SWIPE.code())
                .addValue("now", Timestamp.from(now)));
    }

    public Optional<Rating> find(UUID userId, int movieId) {
        return jdbc.query("select " + COLUMNS + " from app.ratings where user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("user", userId).addValue("movie", movieId), (rs, n) -> map(rs))
                .stream().findFirst();
    }

    /** @return whether a row was deleted */
    public boolean delete(UUID userId, int movieId) {
        return jdbc.update("delete from app.ratings where user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("user", userId).addValue("movie", movieId)) > 0;
    }

    /** Newest first ({@code created_at desc, id desc}), strictly after the keyset cursor when given. */
    public List<Rating> page(UUID userId, Instant afterCreatedAt, UUID afterId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("limit", limit);
        String keyset = "";
        if (afterCreatedAt != null) {
            keyset = " and (created_at, id) < (:afterCreatedAt, :afterId)";
            params.addValue("afterCreatedAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
        }
        return jdbc.query("select " + COLUMNS + " from app.ratings where user_id = :user" + keyset
                + " order by created_at desc, id desc limit :limit", params, (rs, n) -> map(rs));
    }

    public long countBySource(UUID userId, RatingSource source) {
        Long count = jdbc.queryForObject("select count(*) from app.ratings where user_id = :user and source = :source",
                new MapSqlParameterSource("user", userId).addValue("source", source.code()), Long.class);
        return count == null ? 0 : count;
    }

    public long count(UUID userId) {
        Long count = jdbc.queryForObject("select count(*) from app.ratings where user_id = :user",
                new MapSqlParameterSource("user", userId), Long.class);
        return count == null ? 0 : count;
    }

    public void markUnseen(UUID userId, int movieId, Instant now) {
        jdbc.update("""
                insert into app.rating_unseen (user_id, movie_id, created_at) values (:user, :movie, :now)
                on conflict do nothing
                """, new MapSqlParameterSource("user", userId).addValue("movie", movieId).addValue("now", Timestamp.from(now)));
    }

    /** A rated movie is no longer "haven't seen". */
    public void clearUnseen(UUID userId, int movieId) {
        jdbc.update("delete from app.rating_unseen where user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("user", userId).addValue("movie", movieId));
    }

    private static Rating map(ResultSet rs) throws SQLException {
        Date watchedOn = rs.getDate("watched_on");
        return new Rating(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getInt("movie_id"),
                rs.getInt("score"),
                rs.getString("body"),
                rs.getBoolean("contains_spoilers"),
                RatingVisibility.valueOf(rs.getString("visibility")),
                RatingSource.fromCode(rs.getString("source")),
                watchedOn == null ? null : watchedOn.toLocalDate(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
