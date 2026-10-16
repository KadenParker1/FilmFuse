package com.pm.filmfuse.movie.repository;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One batched {@code insert ... on conflict do update}. Plain JDBC rather than JPA: the ids are
 * assigned (TMDB's), so {@code save()} would select every row before writing it.
 */
public class MovieUpsertRepositoryImpl implements MovieUpsertRepository {

    private static final String UPSERT = """
            insert into app.movies (id, title, release_year, runtime, poster_path, backdrop_path, overview,
                                    genre_ids, popularity, vote_average, cached_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update set
                title         = excluded.title,
                release_year  = excluded.release_year,
                runtime       = coalesce(excluded.runtime, movies.runtime),
                poster_path   = excluded.poster_path,
                backdrop_path = coalesce(excluded.backdrop_path, movies.backdrop_path),
                overview      = excluded.overview,
                genre_ids     = excluded.genre_ids,
                popularity    = excluded.popularity,
                vote_average  = excluded.vote_average,
                cached_at     = excluded.cached_at
            """;

    private final JdbcTemplate jdbc;

    public MovieUpsertRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertAll(Collection<CatalogMovie> movies, Instant cachedAt) {
        if (movies.isEmpty()) {
            return;
        }
        // De-duplicated and in id order, so two concurrent batches lock rows in the same order.
        Map<Integer, CatalogMovie> unique = new LinkedHashMap<>();
        movies.stream().sorted(Comparator.comparingInt(CatalogMovie::id)).forEach(m -> unique.put(m.id(), m));
        List<CatalogMovie> rows = List.copyOf(unique.values());
        Timestamp stamp = Timestamp.from(cachedAt);

        jdbc.batchUpdate(UPSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CatalogMovie movie = rows.get(i);
                ps.setInt(1, movie.id());
                ps.setString(2, movie.title());
                ps.setObject(3, movie.releaseYear(), Types.INTEGER);
                ps.setObject(4, movie.runtime(), Types.INTEGER);
                ps.setString(5, movie.posterPath());
                ps.setString(6, movie.backdropPath());
                ps.setString(7, movie.overview());
                ps.setArray(8, ps.getConnection().createArrayOf("int4", movie.genreIds().toArray()));
                ps.setObject(9, movie.popularity(), Types.DOUBLE);
                ps.setObject(10, movie.voteAverage(), Types.DOUBLE);
                ps.setTimestamp(11, stamp);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
