package com.pm.filmfuse.movie.seed;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Spaces catalog requests at most {@code 1 / requestsPerSecond} apart. Deliberately simpler than a
 * token bucket - no bursts - because the seeder is one thread making a long, steady stream of
 * requests. The time source and the sleeper are injected so tests run on a fake clock.
 */
public final class RequestPacer {

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private boolean started;
    private long next;

    public RequestPacer(double requestsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        if (!(requestsPerSecond > 0)) {
            throw new IllegalArgumentException("requestsPerSecond must be positive");
        }
        this.intervalNanos = Math.max(1, Math.round(1_000_000_000d / requestsPerSecond));
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public static RequestPacer system(double requestsPerSecond) {
        return new RequestPacer(requestsPerSecond, System::nanoTime, Sleeper.SYSTEM);
    }

    public Duration interval() {
        return Duration.ofNanos(intervalNanos);
    }

    /** Blocks until the next request may go out, and books the slot after it. */
    public synchronized void acquire() throws InterruptedException {
        long now = nanoTime.getAsLong();
        if (started && now - next < 0) {
            sleeper.sleep(Duration.ofNanos(next - now));
            now = next;
        }
        started = true;
        next = now + intervalNanos;
    }
}
