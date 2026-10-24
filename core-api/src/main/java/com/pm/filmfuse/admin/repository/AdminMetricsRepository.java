package com.pm.filmfuse.admin.repository;

import com.pm.filmfuse.admin.dto.DailyCountDTO;
import com.pm.filmfuse.admin.dto.VoteTiersDTO;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Aggregate SQL for the admin metrics: one statement per block (each a single pass with
 * {@code count(*) filter (...)}), never a query per row. Times are bound from the application clock,
 * days are UTC.
 */
@Repository
public class AdminMetricsRepository {

    public record CatalogCounts(long total, long withCertification, VoteTiersDTO tiers, long rejected,
                                long databaseBytes) {
    }

    public record UserCounts(long total, long new7d, long onboarded) {
    }

    public record SessionCounts(long started7d, long started30d, long picked7d, long picked30d, long activeGroups30d) {
    }

    public record VectorCounts(long dirty, Instant dirtyOldest, long neverBuilt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AdminMetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CatalogCounts catalog() {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where certification is not null) as with_cert,
                       count(*) filter (where vote_count >= 1000) as v1000,
                       count(*) filter (where vote_count >= 100 and vote_count < 1000) as v100,
                       count(*) filter (where vote_count >= 20 and vote_count < 100) as v20,
                       count(*) filter (where vote_count < 20) as vunder20,
                       count(*) filter (where vote_count is null) as vnull,
                       (select count(*) from app.catalog_seed_rejects) as rejected,
                       pg_database_size(current_database()) as db_bytes
                from app.movies
                """, new MapSqlParameterSource(), (rs, row) -> new CatalogCounts(
                rs.getLong("total"),
                rs.getLong("with_cert"),
                new VoteTiersDTO(rs.getLong("v1000"), rs.getLong("v100"), rs.getLong("v20"), rs.getLong("vunder20"),
                        rs.getLong("vnull")),
                rs.getLong("rejected"),
                rs.getLong("db_bytes")));
    }

    public UserCounts users(Instant now) {
        return jdbc.queryForObject("""
                select count(*) as total,
                       count(*) filter (where created_at >= :since7) as new7,
                       count(*) filter (where onboarding_completed_at is not null) as onboarded
                from app.profiles
                """, new MapSqlParameterSource("since7", Timestamp.from(now.minus(Duration.ofDays(7)))),
                (rs, row) -> new UserCounts(rs.getLong("total"), rs.getLong("new7"), rs.getLong("onboarded")));
    }

    /** One count per UTC day for {@code days} days ending today, oldest first, zero-filled. */
    public List<DailyCountDTO> ratingsPerDay(Instant now, int days) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        return jdbc.query("""
                with days as (
                    select cast(d as date) as day
                    from generate_series(cast(:first as date), cast(:today as date), interval '1 day') d
                ), counts as (
                    select cast(created_at at time zone 'UTC' as date) as day, count(*) as n
                    from app.ratings
                    where created_at >= :from and created_at < :until
                    group by 1
                )
                select days.day, coalesce(counts.n, 0) as n
                from days left join counts on counts.day = days.day
                order by days.day
                """, new MapSqlParameterSource("first", first)
                .addValue("today", today)
                .addValue("from", Timestamp.from(first.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("until", Timestamp.from(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())),
                (rs, row) -> new DailyCountDTO(rs.getObject("day", LocalDate.class), rs.getLong("n")));
    }

    public SessionCounts sessions(Instant now) {
        return jdbc.queryForObject("""
                select count(*) filter (where created_at >= :since7) as started7,
                       count(*) as started30,
                       (select count(*) from app.rec_sessions
                        where status = 'DECIDED' and decided_at >= :since7) as picked7,
                       (select count(*) from app.rec_sessions
                        where status = 'DECIDED' and decided_at >= :since30) as picked30,
                       count(distinct group_id) as groups30
                from app.rec_sessions
                where created_at >= :since30
                """, new MapSqlParameterSource("since7", Timestamp.from(now.minus(Duration.ofDays(7))))
                .addValue("since30", Timestamp.from(now.minus(Duration.ofDays(30)))),
                (rs, row) -> new SessionCounts(rs.getLong("started7"), rs.getLong("started30"), rs.getLong("picked7"),
                        rs.getLong("picked30"), rs.getLong("groups30")));
    }

    public VectorCounts vectors() {
        return jdbc.queryForObject("""
                select count(*) filter (where dirty) as dirty,
                       min(dirty_since) as oldest,
                       count(*) filter (where version = 0) as never_built
                from app.user_interest_vectors
                """, new MapSqlParameterSource(), (rs, row) -> {
            Timestamp oldest = rs.getTimestamp("oldest");
            return new VectorCounts(rs.getLong("dirty"), oldest == null ? null : oldest.toInstant(),
                    rs.getLong("never_built"));
        });
    }

    public long openReports() {
        Long count = jdbc.queryForObject("select count(*) from app.reports where status = 'OPEN'",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }
}
