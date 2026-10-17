package com.pm.filmfuse.rating.repository;

import com.pm.filmfuse.rating.model.CommentEntry;
import com.pm.filmfuse.rating.model.CommentRef;
import com.pm.filmfuse.rating.model.RatingRef;
import com.pm.filmfuse.social.model.SocialUser;
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
 * {@code app.rating_comments}. Every read and the insert embed {@link RatingVisibilitySql} (the
 * rating must be visible to the caller; hidden comment authors are left out). Keyset order:
 * {@code created_at asc, id asc} (oldest first).
 */
@Repository
public class CommentRepository {

    /** The rating {@code r} (author {@code a}) is visible to {@code :viewer}. */
    private static final String RATING_VISIBLE = RatingVisibilitySql.visibleTo(true);

    private final NamedParameterJdbcTemplate jdbc;

    public CommentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the comment if the rating is visible to {@code viewer}, in one statement (no
     * check-then-insert gap). @return whether it was stored
     */
    public boolean insertIfVisible(UUID id, UUID viewer, UUID ratingId, String body, Instant now) {
        return jdbc.update("""
                insert into app.rating_comments (id, rating_id, user_id, body, created_at)
                select :id, r.id, :viewer, :body, :now
                from app.ratings r join app.profiles a on a.id = r.user_id
                where r.id = :rating and %s
                """.formatted(RATING_VISIBLE), new MapSqlParameterSource("id", id)
                .addValue("viewer", viewer)
                .addValue("rating", ratingId)
                .addValue("body", body)
                .addValue("now", Timestamp.from(now))) > 0;
    }

    /** Visible comments on {@code ratingId}, oldest first, strictly after the keyset when given. */
    public List<CommentEntry> page(UUID viewer, UUID ratingId, Instant afterCreatedAt, UUID afterId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("viewer", viewer)
                .addValue("rating", ratingId).addValue("limit", limit);
        String keyset = "";
        if (afterCreatedAt != null) {
            keyset = "\n  and (c.created_at, c.id) > (:afterCreatedAt, :afterId)";
            params.addValue("afterCreatedAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
        }
        return jdbc.query(SELECT + "where c.rating_id = :rating and " + RatingVisibilitySql.commentVisibleTo() + keyset
                + "\norder by c.created_at, c.id limit :limit", params, (rs, n) -> map(rs));
    }

    /** One comment as {@code viewer} would see it (used right after an insert). */
    public Optional<CommentEntry> find(UUID commentId) {
        return jdbc.query(SELECT + "where c.id = :id", new MapSqlParameterSource("id", commentId), (rs, n) -> map(rs))
                .stream().findFirst();
    }

    public Optional<CommentRef> findRef(UUID commentId) {
        return jdbc.query("""
                select c.id, c.rating_id, c.user_id, r.user_id as rating_author
                from app.rating_comments c join app.ratings r on r.id = c.rating_id where c.id = :id
                """, new MapSqlParameterSource("id", commentId), (rs, n) -> new CommentRef(
                rs.getObject("id", UUID.class), rs.getObject("rating_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("rating_author", UUID.class)))
                .stream().findFirst();
    }

    /** Whether the comment exists, its rating is visible to {@code viewer} and the comment is too. */
    public boolean isVisible(UUID viewer, UUID commentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from app.rating_comments c
                               join app.profiles ca on ca.id = c.user_id
                               join app.ratings r on r.id = c.rating_id
                               join app.profiles a on a.id = r.user_id
                               where c.id = :id and %s and %s)
                """.formatted(RATING_VISIBLE, RatingVisibilitySql.commentVisibleTo()),
                new MapSqlParameterSource("viewer", viewer).addValue("id", commentId), Boolean.class));
    }

    public Optional<RatingRef> findRating(UUID ratingId) {
        return jdbc.query("select id, user_id, movie_id from app.ratings where id = :id",
                new MapSqlParameterSource("id", ratingId), (rs, n) -> new RatingRef(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getInt("movie_id"))).stream().findFirst();
    }

    public void delete(UUID commentId) {
        jdbc.update("delete from app.rating_comments where id = :id", new MapSqlParameterSource("id", commentId));
    }

    private static final String SELECT = """
            select c.id, c.rating_id, c.user_id, c.body, c.created_at, r.user_id as rating_author,
                   ca.username, ca.display_name, ca.profile_image_url
            from app.rating_comments c
            join app.profiles ca on ca.id = c.user_id
            join app.ratings r on r.id = c.rating_id
            """;

    private static CommentEntry map(ResultSet rs) throws SQLException {
        UUID authorId = rs.getObject("user_id", UUID.class);
        return new CommentEntry(rs.getObject("id", UUID.class), rs.getObject("rating_id", UUID.class),
                new SocialUser(authorId, rs.getString("username"), rs.getString("display_name"),
                        rs.getString("profile_image_url")),
                rs.getObject("rating_author", UUID.class), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant());
    }
}
