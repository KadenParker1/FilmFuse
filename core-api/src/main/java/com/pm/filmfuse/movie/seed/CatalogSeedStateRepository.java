package com.pm.filmfuse.movie.seed;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.catalog_seed_state} (V18): the seeder's resumable page cursor. Each call autocommits. */
@Repository
public class CatalogSeedStateRepository {

    private final JdbcTemplate jdbc;

    public CatalogSeedStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> nextPage(String name) {
        List<Integer> pages = jdbc.queryForList("select next_page from app.catalog_seed_state where name = ?",
                Integer.class, name);
        return pages.stream().findFirst();
    }

    public void saveNextPage(String name, int nextPage) {
        jdbc.update("""
                insert into app.catalog_seed_state (name, next_page, updated_at) values (?, ?, now())
                on conflict (name) do update set next_page = excluded.next_page, updated_at = excluded.updated_at
                """, name, nextPage);
    }

    public void clear(String name) {
        jdbc.update("delete from app.catalog_seed_state where name = ?", name);
    }
}
