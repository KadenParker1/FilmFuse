package com.pm.filmfuse.rating.repository;

import com.pm.filmfuse.rating.dto.FeedScope;
import com.pm.filmfuse.rating.dto.RatingVisibility;
import com.pm.filmfuse.rating.model.FeedCursor;
import com.pm.filmfuse.rating.model.FeedEntry;
import com.pm.filmfuse.rating.model.FeedFilter;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.social.model.SocialUser;
import com.pm.filmfuse.social.service.SocialGraphService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Other people's ratings ({@code /feed}, {@code /movies/{id}/ratings}, {@code /users/{id}/ratings})
 * and {@code app.rating_likes}. Every read embeds {@link RatingVisibilitySql#visibleTo}; author,
 * certification, like count and "liked by me" come back in the same query (no per-row lookups).
 * Keyset order: {@code updated_at desc, id desc}.
 */
@Repository
public class FeedRepository {

    private static final String SELECT = """
            select r.id, r.user_id, r.movie_id, r.score, r.body, r.contains_spoilers, r.visibility,
                   r.created_at, r.updated_at,
                   a.username, a.display_name, a.profile_image_url,
                   m.certification,
                   (select count(*) from app.rating_likes l where l.rating_id = r.id) as like_count,
                   exists (select 1 from app.rating_likes l where l.rating_id = r.id and l.user_id = :viewer)
                       as liked_by_me
            from app.ratings r
            join app.profiles a on a.id = r.user_id
            join app.movies m on m.id = r.movie_id
            where """;

    /** The viewer and their friends: the FRIENDS scope's authors. */
    private static final String FRIENDS_AND_SELF = "r.user_id in (select cast(:viewer as uuid) union all "
            + SocialGraphService.sqlFriendIds(":viewer") + ")";

    private final NamedParameterJdbcTemplate jdbc;

    public FeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} ratings visible to {@code viewer} that match {@code filter}, after {@code after}. */
    public List<FeedEntry> page(UUID viewer, FeedFilter filter, FeedCursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("viewer", viewer).addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT).append(RatingVisibilitySql.visibleTo(filter.includeOwnPrivate()));
        if (filter.scope() == FeedScope.FRIENDS) {
            sql.append("\n  and ").append(FRIENDS_AND_SELF);
        } else if (filter.scope() == FeedScope.EVERYONE) {
            sql.append("\n  and r.visibility = 'PUBLIC'");
        }
        if (filter.movieId() != null) {
            sql.append("\n  and r.movie_id = :movie");
            params.addValue("movie", filter.movieId());
        }
        if (filter.authorId() != null) {
            sql.append("\n  and r.user_id = :author");
            params.addValue("author", filter.authorId());
        }
        if (after != null) {
            sql.append("\n  and (r.updated_at, r.id) < (:afterUpdatedAt, :afterId)");
            params.addValue("afterUpdatedAt", Timestamp.from(after.updatedAt())).addValue("afterId", after.id());
        }
        sql.append("\norder by r.updated_at desc, r.id desc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, n) -> map(rs));
    }

    /** Whether the rating exists and is visible to {@code viewer} anywhere (own PRIVATE ratings included). */
    public boolean isVisible(UUID viewer, UUID ratingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from app.ratings r join app.profiles a on a.id = r.user_id
                               where r.id = :rating and %s)
                """.formatted(RatingVisibilitySql.visibleTo(true)),
                new MapSqlParameterSource("viewer", viewer).addValue("rating", ratingId), Boolean.class));
    }

    /**
     * Likes the rating if it is visible to {@code viewer}. Idempotent: a repeated like keeps the
     * first timestamp. @return whether a row was inserted (false: already liked, or not visible)
     */
    public boolean like(UUID viewer, UUID ratingId, Instant now) {
        return jdbc.update("""
                insert into app.rating_likes (rating_id, user_id, created_at)
                select r.id, :viewer, :now from app.ratings r join app.profiles a on a.id = r.user_id
                where r.id = :rating and %s
                on conflict (rating_id, user_id) do nothing
                """.formatted(RatingVisibilitySql.visibleTo(true)),
                new MapSqlParameterSource("viewer", viewer).addValue("rating", ratingId)
                        .addValue("now", Timestamp.from(now))) > 0;
    }

    /** Idempotent. */
    public void unlike(UUID viewer, UUID ratingId) {
        jdbc.update("delete from app.rating_likes where rating_id = :rating and user_id = :viewer",
                new MapSqlParameterSource("viewer", viewer).addValue("rating", ratingId));
    }

    private static FeedEntry map(ResultSet rs) throws SQLException {
        UUID authorId = rs.getObject("user_id", UUID.class);
        return new FeedEntry(
                rs.getObject("id", UUID.class),
                new SocialUser(authorId, rs.getString("username"), rs.getString("display_name"),
                        rs.getString("profile_image_url")),
                rs.getInt("movie_id"),
                Certification.fromLabel(rs.getString("certification")),
                rs.getInt("score"),
                rs.getString("body"),
                rs.getBoolean("contains_spoilers"),
                RatingVisibility.valueOf(rs.getString("visibility")),
                rs.getLong("like_count"),
                rs.getBoolean("liked_by_me"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
