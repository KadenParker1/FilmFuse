package com.pm.filmfuse.common.jdbc;

import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session-level advisory lock against real Postgres: one holder at a time across threads and
 * sessions (a raw JDBC connection stands in for another JVM), released on every path, and never left
 * behind on a pooled connection. Each test uses its own random key, so nothing leaks between tests.
 */
class SessionAdvisoryLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CatalogJobLock catalogJobLock;
    @Value("${spring.datasource.url}")
    private String url;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    private SessionAdvisoryLock lock() {
        return new SessionAdvisoryLock(dataSource, ThreadLocalRandom.current().nextLong(1L << 40, Long.MAX_VALUE));
    }

    private long advisoryLocksOn(long key) {
        Long count = jdbc.queryForObject("""
                select count(*) from pg_locks where locktype = 'advisory' and objsubid = 1
                and ((classid::bigint << 32) | objid::bigint) = ?
                """, Long.class, key);
        return count == null ? 0 : count;
    }

    @Test
    void isHeldIgnoresTheSameKeyHeldInAnotherDatabaseOfTheCluster() throws Exception {
        SessionAdvisoryLock lock = lock();
        // Same server, the always-present "postgres" database: advisory keys are per database.
        String otherDatabase = url.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1postgres");
        assertThat(otherDatabase).isNotEqualTo(url);
        try (Connection other = DriverManager.getConnection(otherDatabase, username, password);
             PreparedStatement take = other.prepareStatement("select pg_try_advisory_lock(?)")) {
            take.setLong(1, lock.key());
            try (ResultSet rs = take.executeQuery()) {
                assertThat(rs.next() && rs.getBoolean(1)).isTrue();
            }

            assertThat(lock.isHeld()).isFalse();
            assertThat(lock.tryRun(() -> "ran")).contains("ran");
        }
    }

    @Test
    void ofTwoConcurrentAttemptsOneRunsAndTheOtherSkips() throws Exception {
        SessionAdvisoryLock lock = lock();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(() -> lock.tryRun(() -> {
            holding.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "first";
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        Optional<String> second = lock.tryRun(() -> "second");

        assertThat(second).isEmpty();
        assertThat(lock.isHeld()).isTrue();
        release.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).contains("first");
        assertThat(lock.isHeld()).isFalse();
        assertThat(lock.tryRun(() -> "third")).contains("third");
    }

    @Test
    void theLockIsReleasedWhenTheWorkThrows() {
        SessionAdvisoryLock lock = lock();

        assertThatThrownBy(() -> lock.tryRun(() -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(advisoryLocksOn(lock.key())).isZero();
        assertThat(lock.tryRun(() -> 1)).contains(1);
    }

    @Test
    void aHolderInAnotherSessionLikeAnotherJvmKeepsUsOutUntilItLetsGo() throws Exception {
        SessionAdvisoryLock lock = lock();
        try (Connection other = DriverManager.getConnection(url, username, password)) {
            try (PreparedStatement statement = other.prepareStatement("select pg_advisory_lock(?)")) {
                statement.setLong(1, lock.key());
                statement.execute();
            }

            assertThat(lock.tryRun(() -> "blocked")).isEmpty();
            assertThat(lock.isHeld()).isTrue();

            try (PreparedStatement statement = other.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, lock.key());
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next() && rs.getBoolean(1)).isTrue();
                }
            }
        }
        assertThat(lock.tryRun(() -> "free")).contains("free");
    }

    @Test
    void itIsNotReentrantAcrossConnectionsAndNothingLeaksOntoPooledConnections() {
        SessionAdvisoryLock lock = lock();

        Optional<Optional<String>> nested = lock.tryRun(() -> lock.tryRun(() -> "inner"));

        assertThat(nested).contains(Optional.empty());
        // Many runs later every pooled connection is free of the lock (a leak would show up in pg_locks).
        for (int i = 0; i < 25; i++) {
            lock.tryRun(() -> null);
        }
        assertThat(advisoryLocksOn(lock.key())).isZero();
        assertThat(jdbc.queryForObject("select pg_try_advisory_lock(?)", Boolean.class, lock.key())).isTrue();
        assertThat(jdbc.queryForObject("select pg_advisory_unlock(?)", Boolean.class, lock.key())).isTrue();
    }

    @Test
    void theCatalogLockUsesItsDocumentedKeyAndShowsUpInPgLocksWhileHeld() {
        assertThat(CatalogJobLock.KEY).isEqualTo(5063808778890923847L);

        Long classIdAndObjId = catalogJobLock.tryRun(() -> jdbc.queryForObject("""
                select count(*) from pg_locks where locktype = 'advisory' and granted and objsubid = 1
                and classid = 1179009857 and objid = 1414287175
                """, Long.class)).orElseThrow();

        assertThat(classIdAndObjId).isEqualTo(1L);
        assertThat(catalogJobLock.isHeld()).isFalse();
    }
}
