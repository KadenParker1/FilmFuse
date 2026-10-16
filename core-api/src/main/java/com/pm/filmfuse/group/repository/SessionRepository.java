package com.pm.filmfuse.group.repository;

import com.pm.filmfuse.common.json.JsonColumns;
import com.pm.filmfuse.group.dto.SessionFiltersDTO;
import com.pm.filmfuse.group.dto.SessionStatus;
import com.pm.filmfuse.group.dto.Strategy;
import com.pm.filmfuse.group.dto.Vote;
import com.pm.filmfuse.group.model.Session;
import com.pm.filmfuse.group.model.SessionMember;
import com.pm.filmfuse.group.model.SessionResult;
import com.pm.filmfuse.group.model.SessionVote;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.RankedMovie;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * {@code app.rec_sessions} and its members, results and votes, plus {@code app.watch_history}.
 * Access rules (members only, OPEN only) live in SessionService.
 */
@Repository
public class SessionRepository {

    private static final String SESSION_COLUMNS = """
            s.id, s.group_id, g.name as group_name, s.created_by, s.filters::text as filters,
            s.effective_filter::text as effective_filter, s.strategy, s.status, s.picked_movie_id, s.created_at,
            (select count(*) from app.rec_session_members c where c.session_id = s.id) as member_count
            """;
    private static final String FROM = " from app.rec_sessions s left join app.groups g on g.id = s.group_id ";

    private final NamedParameterJdbcTemplate jdbc;

    public SessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSession(UUID id, UUID groupId, UUID createdBy, SessionFiltersDTO filters, ContentFilter effective,
                              Strategy strategy, Instant createdAt) {
        jdbc.update("""
                insert into app.rec_sessions (id, group_id, created_by, filters, effective_filter, strategy, status, created_at)
                values (:id, :group, :createdBy, cast(:filters as jsonb), cast(:effective as jsonb), :strategy, 'OPEN', :now)
                """, new MapSqlParameterSource("id", id)
                .addValue("group", groupId)
                .addValue("createdBy", createdBy)
                .addValue("filters", JsonColumns.write(filters))
                .addValue("effective", effective.toJson())
                .addValue("strategy", strategy.name())
                .addValue("now", Timestamp.from(createdAt)));
    }

    /** {@code hasVector} per member, in the given order. */
    public void insertMembers(UUID sessionId, Map<UUID, Boolean> hasVector) {
        hasVector.forEach((userId, vector) -> jdbc.update("""
                insert into app.rec_session_members (session_id, user_id, has_vector) values (:session, :user, :vector)
                """, new MapSqlParameterSource("session", sessionId).addValue("user", userId).addValue("vector", vector)));
    }

    /** Appends results with ranks {@code firstRank}, {@code firstRank + 1}, ... in list order. */
    public void insertResults(UUID sessionId, List<RankedMovie> results, int firstRank, Instant now) {
        int rank = firstRank;
        for (RankedMovie result : results) {
            Map<String, Double> scores = new LinkedHashMap<>();
            result.memberScores().forEach((user, score) -> scores.put(user.toString(), score));
            jdbc.update("""
                    insert into app.rec_session_results (session_id, movie_id, rank, group_score, member_scores,
                                                         explanation, certification, created_at)
                    values (:session, :movie, :rank, :score, cast(:scores as jsonb), :explanation, :certification, :now)
                    """, new MapSqlParameterSource("session", sessionId)
                    .addValue("movie", result.movieId())
                    .addValue("rank", rank++)
                    .addValue("score", result.groupScore())
                    .addValue("scores", JsonColumns.write(scores))
                    .addValue("explanation", result.explanation())
                    .addValue("certification", result.certification() == null ? null : result.certification().label())
                    .addValue("now", Timestamp.from(now)));
        }
    }

    public Optional<Session> findSession(UUID id) {
        return jdbc.query("select " + SESSION_COLUMNS + FROM + "where s.id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> session(rs)).stream().findFirst();
    }

    /** Locks the session row until the transaction ends (serialises refresh / pick per session). */
    public void lockSession(UUID id) {
        jdbc.query("select id from app.rec_sessions where id = :id for update", new MapSqlParameterSource("id", id),
                rs -> {
                });
    }

    public boolean isMember(UUID sessionId, UUID userId) {
        Boolean member = jdbc.queryForObject("""
                select exists (select 1 from app.rec_session_members where session_id = :session and user_id = :user)
                """, new MapSqlParameterSource("session", sessionId).addValue("user", userId), Boolean.class);
        return Boolean.TRUE.equals(member);
    }

    /** In snapshot order (insertion order is not kept by Postgres, so by user id). */
    public List<SessionMember> findMembers(UUID sessionId) {
        return jdbc.query("""
                select m.user_id, m.has_vector,
                       exists (select 1 from app.rec_session_votes v
                               where v.session_id = m.session_id and v.user_id = m.user_id) as voted
                from app.rec_session_members m where m.session_id = :session order by m.user_id
                """, new MapSqlParameterSource("session", sessionId),
                (rs, i) -> new SessionMember(rs.getObject("user_id", UUID.class), rs.getBoolean("has_vector"),
                        rs.getBoolean("voted")));
    }

    public List<UUID> memberIds(UUID sessionId) {
        return jdbc.queryForList("select user_id from app.rec_session_members where session_id = :session order by user_id",
                new MapSqlParameterSource("session", sessionId), UUID.class);
    }

    /** By rank. */
    public List<SessionResult> findResults(UUID sessionId) {
        return jdbc.query("""
                select movie_id, rank, group_score, member_scores::text as member_scores, explanation, certification
                from app.rec_session_results where session_id = :session order by rank
                """, new MapSqlParameterSource("session", sessionId), (rs, i) -> {
            Map<UUID, Double> scores = new LinkedHashMap<>();
            JsonColumns.readObject(rs.getString("member_scores")).forEach((user, score) -> {
                if (score instanceof Number number) {
                    scores.put(UUID.fromString(user), number.doubleValue());
                }
            });
            return new SessionResult(rs.getInt("movie_id"), rs.getInt("rank"), rs.getDouble("group_score"), scores,
                    rs.getString("explanation"), Certification.fromLabel(rs.getString("certification")));
        });
    }

    public boolean hasResult(UUID sessionId, int movieId) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from app.rec_session_results where session_id = :session and movie_id = :movie)
                """, new MapSqlParameterSource("session", sessionId).addValue("movie", movieId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<SessionVote> findVotes(UUID sessionId) {
        return jdbc.query("select user_id, movie_id, vote from app.rec_session_votes where session_id = :session",
                new MapSqlParameterSource("session", sessionId),
                (rs, i) -> new SessionVote(rs.getObject("user_id", UUID.class), rs.getInt("movie_id"),
                        Vote.valueOf(rs.getString("vote"))));
    }

    public boolean isVetoed(UUID sessionId, int movieId) {
        Boolean vetoed = jdbc.queryForObject("""
                select exists (select 1 from app.rec_session_votes
                               where session_id = :session and movie_id = :movie and vote = 'VETO')
                """, new MapSqlParameterSource("session", sessionId).addValue("movie", movieId), Boolean.class);
        return Boolean.TRUE.equals(vetoed);
    }

    public void upsertVote(UUID sessionId, UUID userId, int movieId, Vote vote, Instant now) {
        jdbc.update("""
                insert into app.rec_session_votes (session_id, user_id, movie_id, vote, created_at, updated_at)
                values (:session, :user, :movie, :vote, :now, :now)
                on conflict (session_id, user_id, movie_id) do update set vote = excluded.vote, updated_at = excluded.updated_at
                """, new MapSqlParameterSource("session", sessionId).addValue("user", userId).addValue("movie", movieId)
                .addValue("vote", vote.name()).addValue("now", Timestamp.from(now)));
    }

    public void deleteVote(UUID sessionId, UUID userId, int movieId) {
        jdbc.update("delete from app.rec_session_votes where session_id = :session and user_id = :user and movie_id = :movie",
                new MapSqlParameterSource("session", sessionId).addValue("user", userId).addValue("movie", movieId));
    }

    public void updateEffectiveFilter(UUID sessionId, ContentFilter effective) {
        jdbc.update("update app.rec_sessions set effective_filter = cast(:effective as jsonb) where id = :id",
                new MapSqlParameterSource("id", sessionId).addValue("effective", effective.toJson()));
    }

    public void decide(UUID sessionId, int movieId, Instant now) {
        jdbc.update("""
                update app.rec_sessions set status = 'DECIDED', picked_movie_id = :movie, decided_at = :now where id = :id
                """, new MapSqlParameterSource("id", sessionId).addValue("movie", movieId)
                .addValue("now", Timestamp.from(now)));
    }

    public void insertWatchHistory(Collection<UUID> userIds, int movieId, UUID sessionId, Instant watchedAt) {
        for (UUID userId : userIds) {
            jdbc.update("""
                    insert into app.watch_history (user_id, movie_id, session_id, watched_at)
                    values (:user, :movie, :session, :now)
                    """, new MapSqlParameterSource("user", userId).addValue("movie", movieId)
                    .addValue("session", sessionId).addValue("now", Timestamp.from(watchedAt)));
        }
    }

    /**
     * Sessions {@code userId} is a member of, newest first (ties by id, descending), optionally only
     * those of {@code groupId}, strictly after the keyset ({@code afterCreatedAt}, {@code afterId}).
     */
    public List<Session> listFor(UUID userId, UUID groupId, Instant afterCreatedAt, UUID afterId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder("select " + SESSION_COLUMNS + FROM + """
                where exists (select 1 from app.rec_session_members m where m.session_id = s.id and m.user_id = :user)
                """);
        if (groupId != null) {
            sql.append(" and s.group_id = :group");
            params.addValue("group", groupId);
        }
        if (afterCreatedAt != null) {
            sql.append(" and (s.created_at, s.id) < (:afterCreatedAt, :afterId)");
            params.addValue("afterCreatedAt", Timestamp.from(afterCreatedAt)).addValue("afterId", afterId);
        }
        sql.append(" order by s.created_at desc, s.id desc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> session(rs));
    }

    private static Session session(ResultSet rs) throws SQLException {
        SessionFiltersDTO filters = JsonColumns.read(rs.getString("filters"), SessionFiltersDTO.class);
        Object picked = rs.getObject("picked_movie_id");
        return new Session(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getString("group_name"), rs.getObject("created_by", UUID.class), filters,
                ContentFilter.fromJson(rs.getString("effective_filter")), Strategy.valueOf(rs.getString("strategy")),
                SessionStatus.valueOf(rs.getString("status")), picked == null ? null : ((Number) picked).intValue(),
                rs.getInt("member_count"), GroupRepository.instant(rs, "created_at"));
    }
}
