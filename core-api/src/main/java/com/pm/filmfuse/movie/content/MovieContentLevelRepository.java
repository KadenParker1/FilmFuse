package com.pm.filmfuse.movie.content;

import com.pm.filmfuse.reco.dto.ContentDimension;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The write side of {@code app.movie_content_levels}. */
@Repository
public class MovieContentLevelRepository {

    private static final String UPSERT = """
            insert into app.movie_content_levels (movie_id, dimension, level, source, confidence, updated_at)
            values (?, ?, ?, ?, ?, ?)
            on conflict (movie_id, dimension) do update set
                level      = excluded.level,
                source     = excluded.source,
                confidence = excluded.confidence,
                updated_at = excluded.updated_at
            where movie_content_levels.source <> 'manual'
            """;

    private final JdbcTemplate jdbc;

    public MovieContentLevelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces {@code source}'s levels for one movie with {@code levels}: rows for dimensions it no
     * longer estimates are removed, and a {@code manual} row is never overwritten or removed.
     */
    public void replace(int movieId, String source, Map<ContentDimension, ContentLevelEstimate> levels, Instant now) {
        List<String> kept = levels.keySet().stream().map(ContentDimension::code).toList();
        jdbc.update("delete from app.movie_content_levels where movie_id = ? and source = ? "
                        + "and not (dimension = any (?))",
                ps -> {
                    ps.setInt(1, movieId);
                    ps.setString(2, source);
                    ps.setArray(3, ps.getConnection().createArrayOf("text", kept.toArray()));
                });
        Timestamp stamp = Timestamp.from(now);
        levels.forEach((dimension, estimate) -> jdbc.update(UPSERT, movieId, dimension.code(),
                estimate.level().code(), source, estimate.confidence(), stamp));
    }
}
