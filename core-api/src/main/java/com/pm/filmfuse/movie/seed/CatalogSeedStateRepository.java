package com.pm.filmfuse.movie.seed;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app.catalog_seed_state} (V18, {@code version} since V19): the seeder's resumable cursors, one
 * row per source ({@code popular}: next page; {@code export}: next 1-based position in the sorted id
 * list, version = the export date; {@code discover}: {@code year * 1000 + page}, version = its
 * settings). Each call autocommits.
 */
@Repository
public class CatalogSeedStateRepository {

    /** A stored cursor; {@code version} null for the popular pages. */
    public record Cursor(int next, String version) {
    }

    private final JdbcTemplate jdbc;

    public CatalogSeedStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> nextPage(String name) {
        return cursor(name).map(Cursor::next);
    }

    public Optional<Cursor> cursor(String name) {
        List<Cursor> rows = jdbc.query("select next_page, version from app.catalog_seed_state where name = ?",
                (rs, i) -> new Cursor(rs.getInt(1), rs.getString(2)), name);
        return rows.stream().findFirst();
    }

    public void saveNextPage(String name, int nextPage) {
        save(name, nextPage, null);
    }

    public void save(String name, int next, String version) {
        jdbc.update("""
                insert into app.catalog_seed_state (name, next_page, version, updated_at) values (?, ?, ?, now())
                on conflict (name) do update set next_page = excluded.next_page, version = excluded.version,
                                                 updated_at = excluded.updated_at
                """, name, next, version);
    }

    public void clear(String name) {
        jdbc.update("delete from app.catalog_seed_state where name = ?", name);
    }
}
