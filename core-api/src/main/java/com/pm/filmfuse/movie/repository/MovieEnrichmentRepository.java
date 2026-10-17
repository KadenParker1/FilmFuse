package com.pm.filmfuse.movie.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.movies.certification_checked_at}: the catalog-enrichment queue. */
@Repository
public class MovieEnrichmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MovieEnrichmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} movies never looked up, most popular first. */
    public List<Integer> unchecked(int limit) {
        return jdbc.queryForList("""
                select id from app.movies where certification_checked_at is null
                order by popularity desc nulls last, id limit :limit
                """, new MapSqlParameterSource("limit", limit), Integer.class);
    }

    public boolean hasUnchecked() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from app.movies where certification_checked_at is null)",
                new MapSqlParameterSource(), Boolean.class));
    }

    public void markChecked(Collection<Integer> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("update app.movies set certification_checked_at = :now where id in (:ids)",
                new MapSqlParameterSource("ids", ids).addValue("now", Timestamp.from(now)));
    }

    public long count() {
        Long count = jdbc.queryForObject("select count(*) from app.movies", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }
}
