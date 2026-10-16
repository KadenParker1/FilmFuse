package com.pm.filmfuse.reco.repository;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.MovieContent;
import java.sql.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code app.movies} + {@code app.movie_content_levels} for the comfort filter: the eligible
 * candidate pool (in SQL) and the content of given movies (to re-check ranker output in Java).
 *
 * <p>The SQL in {@link #eligiblePool} must implement exactly {@link ContentFilter#isEligible} and
 * {@link CandidateCriteria#matches}; ContentFilterIntegrationTest checks the two agree.
 */
@Repository
public class MovieContentRepository {

    /** Certifications in ascending order; {@code array_position} gives the 1-based rank. */
    private static final String CERT_ORDER = "array['G', 'PG', 'PG-13', 'R', 'NC-17']::text[]";
    private static final String LEVEL_ORDER = "array['none', 'mild', 'moderate', 'strong']::text[]";

    private final NamedParameterJdbcTemplate jdbc;

    public MovieContentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ids of cached movies that pass {@code filter} and {@code criteria}, not in {@code excluded},
     * most popular first (ties by id), at most {@code limit}.
     */
    public List<Integer> eligiblePool(ContentFilter filter, CandidateCriteria criteria, Collection<Integer> excluded,
                                      int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit)
                .addValue("allowUnrated", filter.allowUnrated())
                .addValue("excluded", intArrayLiteral(excluded));
        StringBuilder sql = new StringBuilder("select m.id from app.movies m where not (m.id = any (cast(:excluded as int[])))");

        if (filter.maxCertification() != null) {
            sql.append(" and (case when m.certification is null then :allowUnrated else array_position(")
                    .append(CERT_ORDER).append(", m.certification) <= :maxCert end)");
            params.addValue("maxCert", filter.maxCertification().ordinal() + 1);
        }
        int i = 0;
        for (Map.Entry<ContentDimension, ContentLevel> dimensionLimit : filter.contentLimits().entrySet()) {
            if (dimensionLimit.getValue() == ContentLevel.STRONG) {
                continue;
            }
            // Unknown level (no row): passes only when allowUnrated.
            sql.append(" and coalesce((select array_position(").append(LEVEL_ORDER).append(", l.level)")
                    .append(" from app.movie_content_levels l where l.movie_id = m.id and l.dimension = :dim").append(i)
                    .append("), case when :allowUnrated then 0 else 99 end) <= :lim").append(i);
            params.addValue("dim" + i, dimensionLimit.getKey().code()).addValue("lim" + i, dimensionLimit.getValue().ordinal() + 1);
            i++;
        }
        if (criteria.maxRuntime() != null) {
            sql.append(" and (m.runtime is null or m.runtime <= :maxRuntime)");
            params.addValue("maxRuntime", criteria.maxRuntime());
        }
        if (criteria.minYear() != null) {
            sql.append(" and (m.release_year is null or m.release_year >= :minYear)");
            params.addValue("minYear", criteria.minYear());
        }
        if (!criteria.genreIds().isEmpty()) {
            sql.append(" and m.genre_ids && cast(:genreIds as int[])");
            params.addValue("genreIds", intArrayLiteral(criteria.genreIds()));
        }
        sql.append(" order by m.popularity desc nulls last, m.id limit :limit");
        return jdbc.queryForList(sql.toString(), params, Integer.class);
    }

    /** The cached movies among {@code ids}, keyed by id (unknown ids are absent). */
    public Map<Integer, MovieContent> findContent(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", intArrayLiteral(ids));
        Map<Integer, Map<ContentDimension, ContentLevel>> levels = new HashMap<>();
        jdbc.query("select movie_id, dimension, level from app.movie_content_levels where movie_id = any (cast(:ids as int[]))",
                params, rs -> {
                    ContentDimension dimension = ContentDimension.fromCode(rs.getString("dimension"));
                    ContentLevel level = ContentLevel.fromCode(rs.getString("level"));
                    if (dimension != null && level != null) {
                        levels.computeIfAbsent(rs.getInt("movie_id"), k -> new EnumMap<>(ContentDimension.class))
                                .put(dimension, level);
                    }
                });
        Map<Integer, MovieContent> result = new LinkedHashMap<>();
        jdbc.query("select id, certification, runtime, release_year, genre_ids from app.movies "
                + "where id = any (cast(:ids as int[]))", params, rs -> {
            int id = rs.getInt("id");
            result.put(id, new MovieContent(id, Certification.fromLabel(rs.getString("certification")),
                    levels.getOrDefault(id, Map.of()), (Integer) rs.getObject("runtime"),
                    (Integer) rs.getObject("release_year"), ints(rs.getArray("genre_ids"))));
        });
        return result;
    }

    private static List<Integer> ints(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).filter(Objects::nonNull).map(v -> ((Number) v).intValue()).toList();
    }

    /** {@code {1,2,3}} for {@code cast(:x as int[])}; an empty collection is {@code {}}. */
    static String intArrayLiteral(Collection<Integer> values) {
        return values.stream().filter(Objects::nonNull).map(String::valueOf)
                .collect(Collectors.joining(",", "{", "}"));
    }
}
