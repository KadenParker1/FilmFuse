package com.pm.filmfuse.movie.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The current database's size in bytes, for the seeder's {@code max-database-mb} guard. An interface
 * so tests can fake a growing database.
 */
@FunctionalInterface
public interface DatabaseSizeProbe {

    long currentBytes();

    /** {@code pg_database_size(current_database())} - what Supabase's plan limit is measured against. */
    @Component
    class Postgres implements DatabaseSizeProbe {

        private final JdbcTemplate jdbc;

        public Postgres(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public long currentBytes() {
            Long bytes = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
            return bytes == null ? 0 : bytes;
        }
    }
}
