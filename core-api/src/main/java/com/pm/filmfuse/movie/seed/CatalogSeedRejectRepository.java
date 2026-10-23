package com.pm.filmfuse.movie.seed;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app.catalog_seed_rejects} (V19): ids that failed the seeder's quality bar, so re-runs do not
 * fetch them again within {@code quality.reject-ttl}. Each call autocommits.
 */
@Repository
public class CatalogSeedRejectRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogSeedRejectRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Which of {@code ids} were rejected at or after {@code since}. */
    public Set<Integer> rejectedSince(Collection<Integer> ids, Instant since) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                select movie_id from app.catalog_seed_rejects where movie_id in (:ids) and rejected_at >= :since
                """, new MapSqlParameterSource("ids", ids).addValue("since", Timestamp.from(since)), Integer.class));
    }

    public void record(int movieId, Instant at) {
        jdbc.update("""
                insert into app.catalog_seed_rejects (movie_id, rejected_at) values (:id, :at)
                on conflict (movie_id) do update set rejected_at = excluded.rejected_at
                """, new MapSqlParameterSource("id", movieId).addValue("at", Timestamp.from(at)));
    }

    /** Drops rejections older than {@code before} (they would be refetched anyway). */
    public int pruneBefore(Instant before) {
        return jdbc.update("delete from app.catalog_seed_rejects where rejected_at < :before",
                new MapSqlParameterSource("before", Timestamp.from(before)));
    }
}
