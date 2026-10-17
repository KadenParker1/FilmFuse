package com.pm.filmfuse.reco.repository;

import com.pm.filmfuse.common.jdbc.SqlArrays;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.ml.MlUserVector;
import com.pm.filmfuse.reco.model.StoredVector;
import com.pm.filmfuse.reco.model.VectorHistoryEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app.user_interest_vectors} and {@code app.user_interest_vector_history}. The dirty-flag
 * protocol is described in V9; {@link #markDirty} and {@link #storeNewVersion} are its two halves.
 */
@Repository
public class UserVectorRepository {

    private static final String COLUMNS = """
            user_id, interpretable, latent, dim, model_version, version, dirty, dirty_reason, dirty_since, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public UserVectorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks the user's vector dirty with {@code reason} (latest reason wins). {@code dirty_since}
     * becomes the latest mark and strictly increases, so a mark made while a build is running is
     * always seen as newer than the one the build read.
     */
    public void markDirty(UUID userId, VectorChangeReason reason, Instant now) {
        jdbc.update("""
                insert into app.user_interest_vectors (user_id, dirty, dirty_reason, dirty_since)
                values (:id, true, :reason, :now)
                on conflict (user_id) do update set
                    dirty        = true,
                    dirty_reason = excluded.dirty_reason,
                    dirty_since  = case when user_interest_vectors.dirty_since is null then excluded.dirty_since
                                        else greatest(excluded.dirty_since,
                                                      user_interest_vectors.dirty_since + interval '1 microsecond') end
                """, new MapSqlParameterSource("id", userId)
                .addValue("reason", reason.name())
                .addValue("now", Timestamp.from(now)));
    }

    public Optional<StoredVector> find(UUID userId) {
        return jdbc.query("select " + COLUMNS + " from app.user_interest_vectors where user_id = :id",
                new MapSqlParameterSource("id", userId), (rs, n) -> map(rs)).stream().findFirst();
    }

    /** Users dirty since at most {@code cutoff}, longest-waiting first. */
    public List<UUID> findDue(Instant cutoff, int limit) {
        return jdbc.queryForList("""
                select user_id from app.user_interest_vectors
                where dirty and dirty_since <= :cutoff
                order by dirty_since limit :limit
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", limit), UUID.class);
    }

    /**
     * Stores version {@code expectedVersion + 1} and its history row - but only if the row is still
     * at {@code expectedVersion} (the version the build read). A concurrent build that stored first
     * wins and this one is discarded, so versions are never duplicated or out of order. Clears the
     * dirty flag only when {@code dirty_since} still equals {@code seenDirtySince} (no newer mark
     * since the build read the signals). One statement for the vector row, so the checks and the
     * clear are atomic.
     *
     * @return the new version number; empty when another build got there first
     */
    public Optional<Integer> storeNewVersion(UUID userId, int expectedVersion, MlUserVector vector, int dim,
                                             String modelVersion, VectorChangeReason reason, Instant seenDirtySince,
                                             Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", userId)
                .addValue("interpretable", SqlArrays.realLiteral(vector.interpretable()))
                .addValue("latent", SqlArrays.realLiteral(vector.latent()))
                .addValue("dim", dim)
                .addValue("modelVersion", modelVersion)
                .addValue("reason", reason.name())
                .addValue("seen", seenDirtySince == null ? null : Timestamp.from(seenDirtySince))
                .addValue("now", Timestamp.from(now))
                .addValue("expected", expectedVersion);
        List<Integer> stored = jdbc.queryForList("""
                insert into app.user_interest_vectors (user_id, interpretable, latent, dim, model_version, version,
                                                       dirty, updated_at)
                values (:id, cast(:interpretable as real[]), cast(:latent as real[]), :dim, :modelVersion, 1, false, :now)
                on conflict (user_id) do update set
                    interpretable = excluded.interpretable,
                    latent        = excluded.latent,
                    dim           = excluded.dim,
                    model_version = excluded.model_version,
                    version       = user_interest_vectors.version + 1,
                    updated_at    = excluded.updated_at,
                    dirty         = case when user_interest_vectors.dirty_since is not distinct from cast(:seen as timestamptz)
                                         then false else user_interest_vectors.dirty end,
                    dirty_reason  = case when user_interest_vectors.dirty_since is not distinct from cast(:seen as timestamptz)
                                         then null else user_interest_vectors.dirty_reason end,
                    dirty_since   = case when user_interest_vectors.dirty_since is not distinct from cast(:seen as timestamptz)
                                         then null else user_interest_vectors.dirty_since end
                where user_interest_vectors.version = :expected
                returning version
                """, params, Integer.class);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        int version = stored.getFirst();
        jdbc.update("""
                insert into app.user_interest_vector_history (user_id, version, interpretable, latent, dim, model_version,
                                                              reason, created_at)
                values (:id, :version, cast(:interpretable as real[]), cast(:latent as real[]), :dim, :modelVersion,
                        :reason, :now)
                """, params.addValue("version", version));
        return Optional.of(version);
    }

    /** Built vectors of {@code userIds} (version > 0), keyed by user; users without one are absent. */
    public Map<UUID, MlUserVector> builtVectorsOf(Collection<UUID> userIds) {
        Map<UUID, MlUserVector> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select user_id, interpretable, latent from app.user_interest_vectors
                where user_id in (:ids) and version > 0
                """, new MapSqlParameterSource("ids", userIds), rs -> {
            result.put(rs.getObject("user_id", UUID.class), new MlUserVector(
                    SqlArrays.doubles(rs.getArray("interpretable")), SqlArrays.doubles(rs.getArray("latent"))));
        });
        return result;
    }

    /** Newest first. */
    public List<VectorHistoryEntry> history(UUID userId, int limit) {
        return jdbc.query("""
                select version, interpretable, model_version, reason, created_at
                from app.user_interest_vector_history where user_id = :id
                order by version desc limit :limit
                """, new MapSqlParameterSource("id", userId).addValue("limit", limit), (rs, n) -> new VectorHistoryEntry(
                rs.getInt("version"), SqlArrays.doubles(rs.getArray("interpretable")), rs.getString("model_version"),
                VectorChangeReason.valueOf(rs.getString("reason")), rs.getTimestamp("created_at").toInstant()));
    }

    private static StoredVector map(ResultSet rs) throws SQLException {
        String reason = rs.getString("dirty_reason");
        Timestamp dirtySince = rs.getTimestamp("dirty_since");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new StoredVector(
                rs.getObject("user_id", UUID.class),
                SqlArrays.doubles(rs.getArray("interpretable")),
                SqlArrays.doubles(rs.getArray("latent")),
                (Integer) rs.getObject("dim"),
                rs.getString("model_version"),
                rs.getInt("version"),
                rs.getBoolean("dirty"),
                reason == null ? null : VectorChangeReason.valueOf(reason),
                dirtySince == null ? null : dirtySince.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant());
    }
}
