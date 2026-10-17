package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.Friendship;
import com.pm.filmfuse.social.model.FriendshipStatus;
import com.pm.filmfuse.social.model.FriendsCursor;
import com.pm.filmfuse.social.model.PendingRequest;
import com.pm.filmfuse.social.model.Suggestion;
import com.pm.filmfuse.social.model.UserPair;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app.friendships} and {@code app.friend_closeness}. Plain SQL: canonical pairs, upserts and
 * friend-of-friend queries read far more clearly here than through JPA. Only
 * {@link com.pm.filmfuse.social.service.SocialGraphService} uses this class.
 */
@Repository
public class FriendshipRepository {

    /** "The other member of f, seen from :me" - used wherever a query walks the caller's edges. */
    private static final String OTHER = "case when f.user_low = :me then f.user_high else f.user_low end";

    private static final String MY_FRIENDS = """
            select case when user_low = :me then user_high else user_low end as id
            from app.friendships
            where status = 'ACCEPTED' and (user_low = :me or user_high = :me)
            """;

    /**
     * Effective directional closeness of :me to p: the implicit score, raised to at least 0.8 by the
     * explicit flag. A missing closeness row (never expected) reads as the defaults.
     */
    private static final String EFFECTIVE_CLOSENESS = """
            case when coalesce(c.explicit_close, false)
                 then greatest(coalesce(c.score, 0.5::real), 0.8::real)
                 else coalesce(c.score, 0.5::real) end""";

    private static final String FRIENDS = """
            select * from (
                select %s, f.accepted_at,
                       coalesce(c.explicit_close, false) as explicit_close,
                       %s as closeness,
                       coalesce(p.username, '') as sort_name
                from app.friendships f
                join app.profiles p on p.id = %s
                left join app.friend_closeness c on c.user_id = :me and c.friend_id = p.id
                where f.status = 'ACCEPTED' and (f.user_low = :me or f.user_high = :me)
            ) t
            """.formatted(SocialRows.USER_COLUMNS, EFFECTIVE_CLOSENESS, OTHER);

    private static final String FRIENDS_ORDER = " order by explicit_close desc, closeness desc, sort_name asc, id asc";

    /** Rows strictly after the cursor in {@link #FRIENDS_ORDER}. */
    private static final String FRIENDS_AFTER = """
             where (explicit_close < :cExplicit)
                or (explicit_close = :cExplicit and closeness < :cCloseness)
                or (explicit_close = :cExplicit and closeness = :cCloseness and sort_name > :cName)
                or (explicit_close = :cExplicit and closeness = :cCloseness and sort_name = :cName and id > :cId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FriendshipRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises every read-then-write on one pair until the surrounding transaction ends, whichever
     * of the two started it. Must be called inside a transaction.
     */
    public void lockPair(UserPair pair) {
        jdbc.queryForList("select pg_advisory_xact_lock(:key)", Map.of("key", pair.lockKey()));
    }

    public Optional<Friendship> find(UserPair pair) {
        return jdbc.query("""
                        select user_low, user_high, status, requested_by, created_at, accepted_at
                        from app.friendships where user_low = :low and user_high = :high
                        """, pairParams(pair), FriendshipRepository::friendship)
                .stream().findFirst();
    }

    /** All rows between {@code me} and any of {@code others}, keyed by the other user. */
    public Map<UUID, Friendship> findBetween(UUID me, Collection<UUID> others) {
        Map<UUID, Friendship> result = new LinkedHashMap<>();
        if (others.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        select user_low, user_high, status, requested_by, created_at, accepted_at
                        from app.friendships
                        where (user_low = :me and user_high in (:others)) or (user_high = :me and user_low in (:others))
                        """, new MapSqlParameterSource("me", me).addValue("others", others),
                rs -> {
                    Friendship friendship = friendship(rs, 0);
                    result.put(friendship.pair().other(me), friendship);
                });
        return result;
    }

    /** True when a new pending row was stored; false when the pair already had a row. */
    public boolean insertPending(UserPair pair, UUID requestedBy) {
        return jdbc.update("""
                insert into app.friendships (user_low, user_high, status, requested_by)
                values (:low, :high, 'PENDING', :requestedBy)
                on conflict (user_low, user_high) do nothing
                """, pairParams(pair).addValue("requestedBy", requestedBy)) == 1;
    }

    /** Turns a pending row into a friendship; false when there was no pending row. */
    public boolean accept(UserPair pair) {
        return jdbc.update("""
                update app.friendships set status = 'ACCEPTED', accepted_at = now()
                where user_low = :low and user_high = :high and status = 'PENDING'
                """, pairParams(pair)) == 1;
    }

    /** Friends now, whatever the pair's row said before (a pending request either way, or none). */
    public void upsertAccepted(UserPair pair, UUID requestedBy) {
        jdbc.update("""
                insert into app.friendships (user_low, user_high, status, requested_by, accepted_at)
                values (:low, :high, 'ACCEPTED', :requestedBy, now())
                on conflict (user_low, user_high) do update
                    set status = 'ACCEPTED', accepted_at = coalesce(friendships.accepted_at, now())
                """, pairParams(pair).addValue("requestedBy", requestedBy));
    }

    /** Deletes the pair's row only if it is a pending request from {@code requestedBy}. */
    public boolean deletePendingFrom(UserPair pair, UUID requestedBy) {
        return jdbc.update("""
                delete from app.friendships
                where user_low = :low and user_high = :high and status = 'PENDING' and requested_by = :requestedBy
                """, pairParams(pair).addValue("requestedBy", requestedBy)) == 1;
    }

    public boolean deleteAccepted(UserPair pair) {
        return jdbc.update("""
                delete from app.friendships where user_low = :low and user_high = :high and status = 'ACCEPTED'
                """, pairParams(pair)) == 1;
    }

    /** Removes the pair's row whatever it is. */
    public void delete(UserPair pair) {
        jdbc.update("delete from app.friendships where user_low = :low and user_high = :high", pairParams(pair));
    }

    /** One closeness row each way, at the defaults; existing rows are kept. */
    public void ensureCloseness(UserPair pair) {
        jdbc.update("""
                insert into app.friend_closeness (user_id, friend_id)
                values (:low, :high), (:high, :low)
                on conflict (user_id, friend_id) do nothing
                """, pairParams(pair));
    }

    public void deleteCloseness(UserPair pair) {
        jdbc.update("""
                delete from app.friend_closeness
                where (user_id = :low and friend_id = :high) or (user_id = :high and friend_id = :low)
                """, pairParams(pair));
    }

    public void setExplicitClose(UUID user, UUID friend, boolean explicitClose) {
        jdbc.update("""
                insert into app.friend_closeness (user_id, friend_id, explicit_close)
                values (:me, :friend, :explicit)
                on conflict (user_id, friend_id) do update
                    set explicit_close = excluded.explicit_close, updated_at = now()
                """, new MapSqlParameterSource("me", user).addValue("friend", friend).addValue("explicit", explicitClose));
    }

    /**
     * Sets the implicit {@code score} of existing closeness rows (one per direction). Update only:
     * a friendship ended since the scores were computed has no rows left, and none is recreated.
     *
     * @return how many rows were updated
     */
    public int updateImplicitScores(List<ClosenessRow> scores) {
        if (scores.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = scores.stream()
                .map(row -> new MapSqlParameterSource("me", row.from()).addValue("friend", row.to())
                        .addValue("score", row.closeness()))
                .toArray(MapSqlParameterSource[]::new);
        int updated = 0;
        for (int n : jdbc.batchUpdate("""
                update app.friend_closeness set score = :score, updated_at = now()
                where user_id = :me and friend_id = :friend
                """, batch)) {
            updated += Math.max(n, 0);
        }
        return updated;
    }

    /** Effective closeness of {@code user} to {@code friend}; empty when they are not friends. */
    public Optional<Float> closeness(UUID user, UUID friend) {
        return friend(user, friend).map(FriendEdge::closeness);
    }

    /**
     * Effective directional closeness for every accepted friendship among {@code members}, as
     * (from, to, closeness) rows - both directions of each pair. Pairs that are not friends are absent.
     */
    public List<ClosenessRow> closenessAmong(Collection<UUID> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select e.me, e.other, %s as closeness from (
                    select f.user_low as me, f.user_high as other from app.friendships f
                    where f.status = 'ACCEPTED' and f.user_low in (:ids) and f.user_high in (:ids)
                    union all
                    select f.user_high, f.user_low from app.friendships f
                    where f.status = 'ACCEPTED' and f.user_low in (:ids) and f.user_high in (:ids)
                ) e
                left join app.friend_closeness c on c.user_id = e.me and c.friend_id = e.other
                """.formatted(EFFECTIVE_CLOSENESS), new MapSqlParameterSource("ids", members),
                (rs, i) -> new ClosenessRow(rs.getObject("me", UUID.class), rs.getObject("other", UUID.class),
                        rs.getFloat("closeness")));
    }

    /** One directed closeness value. */
    public record ClosenessRow(UUID from, UUID to, float closeness) {
    }

    public List<UUID> friendIds(UUID user) {
        return jdbc.queryForList(MY_FRIENDS, Map.of("me", user), UUID.class);
    }

    public int friendCount(UUID user) {
        Integer count = jdbc.queryForObject("select count(*) from (" + MY_FRIENDS + ") t", Map.of("me", user),
                Integer.class);
        return count == null ? 0 : count;
    }

    public int mutualFriendCount(UUID a, UUID b) {
        Integer count = jdbc.queryForObject("""
                        select count(*) from (
                            select case when user_low = :a then user_high else user_low end
                            from app.friendships where status = 'ACCEPTED' and (user_low = :a or user_high = :a)
                            intersect
                            select case when user_low = :b then user_high else user_low end
                            from app.friendships where status = 'ACCEPTED' and (user_low = :b or user_high = :b)
                        ) t
                        """, new MapSqlParameterSource("a", a).addValue("b", b), Integer.class);
        return count == null ? 0 : count;
    }

    /** One page of {@code user}'s friends after {@code cursor} (null = first page). */
    public List<FriendEdge> friends(UUID user, FriendsCursor cursor, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("me", user).addValue("limit", limit);
        String sql = FRIENDS;
        if (cursor != null) {
            sql += FRIENDS_AFTER;
            params.addValue("cExplicit", cursor.explicitClose())
                    .addValue("cCloseness", cursor.closeness())
                    .addValue("cName", cursor.sortName())
                    .addValue("cId", cursor.id());
        }
        return jdbc.query(sql + FRIENDS_ORDER + " limit :limit", params, FriendshipRepository::friendEdge);
    }

    public Optional<FriendEdge> friend(UUID user, UUID friend) {
        return jdbc.query(FRIENDS + " where id = :friend",
                        new MapSqlParameterSource("me", user).addValue("friend", friend), FriendshipRepository::friendEdge)
                .stream().findFirst();
    }

    /** Pending requests involving {@code user}, newest first. */
    public List<PendingRequest> pending(UUID user, boolean outgoing) {
        return jdbc.query("""
                        select %s, f.created_at
                        from app.friendships f
                        join app.profiles p on p.id = %s
                        where f.status = 'PENDING' and (f.user_low = :me or f.user_high = :me)
                          and (f.requested_by = :me) = :outgoing
                        order by f.created_at desc, p.id
                        """.formatted(SocialRows.USER_COLUMNS, OTHER),
                new MapSqlParameterSource("me", user).addValue("outgoing", outgoing),
                (rs, i) -> new PendingRequest(SocialRows.user(rs), outgoing, SocialRows.instant(rs, "created_at")));
    }

    /**
     * Friends of {@code user}'s friends who have a username, are not suspended, have no friendship
     * or pending request with {@code user}, and no block either way - ranked by how many friends
     * they share with {@code user}, then username. Each carries up to {@code sampleSize} of those
     * shared friends, closest to {@code user} first.
     */
    public List<Suggestion> suggestions(UUID user, int limit, int sampleSize) {
        String reach = """
                with my_friends as (%s),
                reach as (
                    select m.id as via, case when f.user_low = m.id then f.user_high else f.user_low end as candidate
                    from my_friends m
                    join app.friendships f on f.status = 'ACCEPTED' and (f.user_low = m.id or f.user_high = m.id)
                )
                """.formatted(MY_FRIENDS);
        MapSqlParameterSource params = new MapSqlParameterSource("me", user)
                .addValue("limit", limit).addValue("sample", sampleSize);

        Map<UUID, Integer> counts = new LinkedHashMap<>();
        jdbc.query(reach + """
                select r.candidate, count(*) as mutual
                from reach r
                join app.profiles p on p.id = r.candidate
                where r.candidate <> :me
                  and p.username is not null
                  and p.status <> 'SUSPENDED'
                  and not exists (select 1 from app.friendships x
                                  where x.user_low = least(:me, r.candidate) and x.user_high = greatest(:me, r.candidate))
                  and not exists (select 1 from app.blocks b
                                  where (b.blocker_id = :me and b.blocked_id = r.candidate)
                                     or (b.blocker_id = r.candidate and b.blocked_id = :me))
                group by r.candidate, p.username
                order by mutual desc, p.username asc
                limit :limit
                """, params, rs -> {
            counts.put(rs.getObject("candidate", UUID.class), rs.getInt("mutual"));
        });
        if (counts.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<UUID>> samples = new LinkedHashMap<>();
        jdbc.query(reach + """
                select candidate, via from (
                    select r.candidate, r.via,
                           row_number() over (partition by r.candidate
                                              order by %s desc, coalesce(p.username, '') asc, p.id asc) as rn
                    from reach r
                    join app.profiles p on p.id = r.via
                    left join app.friend_closeness c on c.user_id = :me and c.friend_id = r.via
                    where r.candidate in (:candidates)
                ) t
                where rn <= :sample
                order by candidate, rn
                """.formatted(EFFECTIVE_CLOSENESS), params.addValue("candidates", counts.keySet()), rs -> {
            samples.computeIfAbsent(rs.getObject("candidate", UUID.class), k -> new ArrayList<>())
                    .add(rs.getObject("via", UUID.class));
        });

        List<Suggestion> result = new ArrayList<>(counts.size());
        counts.forEach((candidate, mutual) ->
                result.add(new Suggestion(candidate, mutual, List.copyOf(samples.getOrDefault(candidate, List.of())))));
        return result;
    }

    private static MapSqlParameterSource pairParams(UserPair pair) {
        return new MapSqlParameterSource("low", pair.low()).addValue("high", pair.high());
    }

    private static Friendship friendship(ResultSet rs, int rowNum) throws SQLException {
        return new Friendship(
                new UserPair(rs.getObject("user_low", UUID.class), rs.getObject("user_high", UUID.class)),
                FriendshipStatus.valueOf(rs.getString("status")),
                rs.getObject("requested_by", UUID.class),
                SocialRows.instant(rs, "created_at"),
                SocialRows.instant(rs, "accepted_at"));
    }

    private static FriendEdge friendEdge(ResultSet rs, int rowNum) throws SQLException {
        return new FriendEdge(SocialRows.user(rs), rs.getBoolean("explicit_close"), rs.getFloat("closeness"),
                SocialRows.instant(rs, "accepted_at"));
    }
}
