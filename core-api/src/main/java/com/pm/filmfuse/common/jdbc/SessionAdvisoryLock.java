package com.pm.filmfuse.common.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * A Postgres <b>session-level</b> advisory lock ({@code pg_try_advisory_lock(key)}) that keeps work
 * from running in two places at once - across threads, and across JVMs sharing the database (e.g. the
 * seed CLI beside the deployed app). The lock lives on one dedicated connection taken from the pool for
 * the whole run: it is released explicitly ({@code pg_advisory_unlock}) on every path, and if that
 * fails the connection is evicted from the pool instead of returned, because a pooled connection would
 * otherwise carry the lock on to its next user. If the connection itself dies, Postgres drops the lock
 * with the session.
 *
 * <p>Needs a session-mode connection (direct, or Supabase's session pooler as {@code DATABASE_URL} is);
 * a transaction-mode pooler cannot hold session locks. Not reentrant across connections: a second
 * {@link #tryRun} on the same key, even from the same thread, does not get it.
 */
public final class SessionAdvisoryLock {

    private static final Logger log = LoggerFactory.getLogger(SessionAdvisoryLock.class);

    private final DataSource dataSource;
    private final long key;

    public SessionAdvisoryLock(DataSource dataSource, long key) {
        this.dataSource = dataSource;
        this.key = key;
    }

    public long key() {
        return key;
    }

    /**
     * Runs {@code work} while holding the lock.
     *
     * @return the work's result, or empty when someone else holds the lock (the work did not run)
     */
    public <T> Optional<T> tryRun(Supplier<T> work) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException ex) {
            throw new DataAccessResourceFailureException("No connection for advisory lock " + key, ex);
        }
        boolean released = false;
        boolean locked = false;
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            locked = call(connection, "select pg_try_advisory_lock(?)");
            if (!locked) {
                released = true;
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                released = unlock(connection);
            }
        } catch (SQLException ex) {
            throw new DataAccessResourceFailureException("Advisory lock " + key + " failed", ex);
        } finally {
            close(connection, locked && !released);
        }
    }

    /** Whether anyone (any session) holds the lock right now. */
    public boolean isHeld() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select exists (select 1 from pg_locks where locktype = 'advisory' and granted
                                    and objsubid = 1 and ((classid::bigint << 32) | objid::bigint) = ?)
                     """)) {
            statement.setLong(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException ex) {
            throw new DataAccessResourceFailureException("Could not read pg_locks", ex);
        }
    }

    private boolean unlock(Connection connection) {
        try {
            boolean unlocked = call(connection, "select pg_advisory_unlock(?)");
            if (!unlocked) {
                log.warn("Advisory lock {} was not held at release", key);
            }
            return unlocked;
        } catch (SQLException | RuntimeException ex) {
            log.warn("Could not release advisory lock {} ({}); evicting its connection", key, ex.getMessage());
            return false;
        }
    }

    private boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Returns the connection, or - if it may still hold the lock - closes the physical connection. */
    private void close(Connection connection, boolean mayHoldLock) {
        try {
            if (mayHoldLock) {
                if (dataSource.isWrapperFor(HikariDataSource.class)) {
                    dataSource.unwrap(HikariDataSource.class).evictConnection(connection);
                    return;
                }
                connection.abort(Runnable::run);
            }
            connection.close();
        } catch (SQLException | RuntimeException ex) {
            log.warn("Could not close the advisory lock {} connection: {}", key, ex.getMessage());
        }
    }
}
