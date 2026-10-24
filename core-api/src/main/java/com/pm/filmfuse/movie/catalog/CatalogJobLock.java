package com.pm.filmfuse.movie.catalog;

import com.pm.filmfuse.common.jdbc.SessionAdvisoryLock;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Cross-process mutual exclusion for the catalog writers: the seed CLI ({@code seed} profile), the
 * nightly top-up, the weekly refresh ({@code CatalogRefreshJob}) and {@code CatalogEnrichmentJob} may
 * run in different JVMs against the same database, so only one of them runs at a time. A Postgres
 * session-level advisory lock on the fixed key {@link #KEY} held on a dedicated connection for the
 * whole run (see {@link SessionAdvisoryLock}): a job that cannot get it skips (scheduled) or reports
 * {@code ALREADY_RUNNING} (manual / CLI). The synchronous request-path enrichment
 * ({@code ContentFilterService}) is a handful of idempotent upserts and does not take it.
 */
@Component
public class CatalogJobLock {

    /**
     * {@code pg_try_advisory_lock(5063808778890923847)} = the ASCII bytes "FFCATLOG" as a big-endian
     * bigint. Fixed and documented so an operator can find it in {@code pg_locks}
     * ({@code locktype = 'advisory'}, {@code objsubid = 1}, {@code classid = 1179009857},
     * {@code objid = 1414287175}). The social graph's transaction-scoped pair locks use the same key
     * space but hash two UUIDs to 64 bits, so a clash is a 1-in-2^64 chance per friend pair.
     */
    public static final long KEY = 0x4646_4341_544C_4F47L;

    private final SessionAdvisoryLock lock;

    @Autowired
    public CatalogJobLock(DataSource dataSource) {
        this(new SessionAdvisoryLock(dataSource, KEY));
    }

    public CatalogJobLock(SessionAdvisoryLock lock) {
        this.lock = lock;
    }

    /** Runs {@code work} under the catalog lock; empty when another catalog job holds it. */
    public <T> Optional<T> tryRun(Supplier<T> work) {
        return lock.tryRun(work);
    }

    /** Whether any process holds the catalog lock right now. */
    public boolean isHeld() {
        return lock.isHeld();
    }
}
