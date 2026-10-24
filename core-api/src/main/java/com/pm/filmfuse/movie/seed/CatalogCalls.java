package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.movie.catalog.CatalogAuthenticationException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The catalog request discipline shared by the seeder and the weekly refresh: every request (retries
 * included) goes through the {@link RequestPacer}; 5xx / 429 / timeouts back off
 * {@code initial-backoff * 2^attempt} (at most a minute) and give up after {@code max-retries};
 * not-found passes straight through; {@code auth-failure-limit} consecutive 401s abort the run
 * ({@link Abort}). One instance per run - it counts the consecutive 401s.
 */
final class CatalogCalls {

    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    private static final long MB = 1024L * 1024;

    private final RequestPacer pacer;
    private final Sleeper sleeper;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final int authFailureLimit;
    private int consecutiveAuthFailures;

    CatalogCalls(RequestPacer pacer, Sleeper sleeper, CatalogSeedProperties properties) {
        this.pacer = pacer;
        this.sleeper = sleeper;
        this.maxRetries = properties.maxRetries();
        this.initialBackoff = properties.initialBackoff();
        this.authFailureLimit = properties.authFailureLimit();
    }

    int maxRetries() {
        return maxRetries;
    }

    /** One paced catalog request with retries. */
    <T> T call(Supplier<T> request) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            pacer.acquire();
            try {
                T result = request.get();
                consecutiveAuthFailures = 0;
                return result;
            } catch (CatalogAuthenticationException ex) {
                if (++consecutiveAuthFailures >= authFailureLimit) {
                    throw new Abort("the catalog rejected the access token " + consecutiveAuthFailures
                            + " times in a row (401) - check TMDB_ACCESS_TOKEN");
                }
                if (attempt >= maxRetries) {
                    throw ex;
                }
            } catch (NotFoundException ex) {
                consecutiveAuthFailures = 0;
                throw ex;
            } catch (UpstreamUnavailableException ex) {
                consecutiveAuthFailures = 0;
                if (attempt >= maxRetries) {
                    throw ex;
                }
            }
            sleeper.sleep(backoff(initialBackoff, attempt));
        }
    }

    static Duration backoff(Duration initialBackoff, int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /**
     * The {@code max-database-mb} guard (0 = off): throws {@link Abort} once the database is bigger,
     * or when its size cannot be read.
     */
    static void checkDatabaseSize(DatabaseSizeProbe probe, int limitMb) {
        if (limitMb == 0) {
            return;
        }
        long bytes;
        try {
            bytes = probe.currentBytes();
        } catch (RuntimeException ex) {
            throw new Abort("could not read the database size for the max-database-mb guard (" + ex.getMessage()
                    + "); set filmfuse.catalog.seed.max-database-mb=0 to run without it");
        }
        if (bytes > limitMb * MB) {
            throw new Abort("database size " + bytes / MB + " MB exceeds max-database-mb=" + limitMb
                    + " - stopping before it fills up. Cursors are kept: raise filmfuse.catalog.seed.max-database-mb "
                    + "(e.g. after a plan upgrade) and run again to resume");
        }
    }

    /** Stops the whole run; never caught per movie. */
    static final class Abort extends RuntimeException {
        Abort(String message) {
            super(message, null, false, false);
        }
    }
}
