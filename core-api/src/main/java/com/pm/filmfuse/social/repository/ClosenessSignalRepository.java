package com.pm.filmfuse.social.repository;

import com.pm.filmfuse.social.model.UserPair;
import java.sql.Array;
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
 * The inputs of implicit closeness, for {@link com.pm.filmfuse.social.service.ClosenessJob}: every
 * accepted friendship with the number of sessions the two watched together (both have a
 * {@code watch_history} row for the same session picked since {@code since}) and both users'
 * interpretable vectors. Read-only; it reads {@code watch_history} (group module) and
 * {@code user_interest_vectors} (reco module) directly so the whole input is one query.
 */
@Repository
public class ClosenessSignalRepository {

    /** One friendship's signals; a vector is null when that user has none yet. */
    public record PairSignals(UserPair pair, int coSessions, float[] lowVector, float[] highVector) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ClosenessSignalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PairSignals> acceptedPairSignals(Instant since) {
        return jdbc.query("""
                with together as (
                    select a.user_id as low, b.user_id as high, count(distinct a.session_id) as sessions
                    from app.watch_history a
                    join app.watch_history b on b.session_id = a.session_id and a.user_id < b.user_id
                    where a.session_id is not null and a.watched_at >= :since and b.watched_at >= :since
                    group by a.user_id, b.user_id
                )
                select f.user_low, f.user_high, coalesce(t.sessions, 0) as sessions,
                       vl.interpretable as low_vector, vh.interpretable as high_vector
                from app.friendships f
                left join together t on t.low = f.user_low and t.high = f.user_high
                left join app.user_interest_vectors vl on vl.user_id = f.user_low and vl.version > 0
                left join app.user_interest_vectors vh on vh.user_id = f.user_high and vh.version > 0
                where f.status = 'ACCEPTED'
                order by f.user_low, f.user_high
                """, new MapSqlParameterSource("since", Timestamp.from(since)), (rs, n) -> new PairSignals(
                new UserPair(rs.getObject("user_low", UUID.class), rs.getObject("user_high", UUID.class)),
                rs.getInt("sessions"), floats(rs, "low_vector"), floats(rs, "high_vector")));
    }

    private static float[] floats(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        Object[] values = (Object[]) array.getArray();
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] == null ? 0f : ((Number) values[i]).floatValue();
        }
        return result;
    }
}
