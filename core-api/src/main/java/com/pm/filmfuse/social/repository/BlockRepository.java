package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.BlockedUser;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.blocks}. Only {@link com.pm.filmfuse.social.service.SocialGraphService} uses this class. */
@Repository
public class BlockRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BlockRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: blocking twice keeps the first timestamp. */
    public void block(UUID blocker, UUID blocked) {
        jdbc.update("""
                insert into app.blocks (blocker_id, blocked_id) values (:blocker, :blocked)
                on conflict (blocker_id, blocked_id) do nothing
                """, params(blocker, blocked));
    }

    public void unblock(UUID blocker, UUID blocked) {
        jdbc.update("delete from app.blocks where blocker_id = :blocker and blocked_id = :blocked",
                params(blocker, blocked));
    }

    public boolean exists(UUID blocker, UUID blocked) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from app.blocks where blocker_id = :blocker and blocked_id = :blocked)
                """, params(blocker, blocked), Boolean.class));
    }

    public boolean existsEitherWay(UUID a, UUID b) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from app.blocks
                               where (blocker_id = :blocker and blocked_id = :blocked)
                                  or (blocker_id = :blocked and blocked_id = :blocker))
                """, params(a, b), Boolean.class));
    }

    /** Everyone who has blocked {@code user}. */
    public Set<UUID> blockersOf(UUID user) {
        return new HashSet<>(jdbc.queryForList("select blocker_id from app.blocks where blocked_id = :user",
                new MapSqlParameterSource("user", user), UUID.class));
    }

    /** Which of {@code others} {@code blocker} has blocked. */
    public Set<UUID> blockedAmong(UUID blocker, Collection<UUID> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select blocked_id from app.blocks where blocker_id = :blocker and blocked_id in (:others)",
                new MapSqlParameterSource("blocker", blocker).addValue("others", others), UUID.class));
    }

    /** Everyone {@code blocker} has blocked, most recent first. */
    public List<BlockedUser> blockedBy(UUID blocker) {
        return jdbc.query("""
                        select %s, b.created_at
                        from app.blocks b join app.profiles p on p.id = b.blocked_id
                        where b.blocker_id = :blocker
                        order by b.created_at desc, p.id
                        """.formatted(SocialRows.USER_COLUMNS),
                new MapSqlParameterSource("blocker", blocker),
                (rs, i) -> new BlockedUser(SocialRows.user(rs), SocialRows.instant(rs, "created_at")));
    }

    private static MapSqlParameterSource params(UUID blocker, UUID blocked) {
        return new MapSqlParameterSource("blocker", blocker).addValue("blocked", blocked);
    }
}
