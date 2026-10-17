package com.pm.filmfuse.common.ratelimit;

/**
 * A classic token bucket: holds up to {@code capacity} tokens and refills continuously at
 * {@code capacity} per {@code periodNanos}. Times are nanoseconds on the caller's clock, so tests
 * can move time without sleeping. Thread-safe; each bucket is touched by one user or IP at a time
 * in practice, so the monitor is uncontended.
 */
final class TokenBucket {

    private final long capacity;
    private final double nanosPerToken;
    private double tokens;
    private long updatedAt;

    TokenBucket(long capacity, long periodNanos, long now) {
        if (capacity < 1 || periodNanos < 1) {
            throw new IllegalArgumentException("capacity and period must be positive");
        }
        this.capacity = capacity;
        this.nanosPerToken = (double) periodNanos / capacity;
        this.tokens = capacity;
        this.updatedAt = now;
    }

    /** Takes one token. @return 0 when taken, otherwise the nanoseconds until one is available */
    synchronized long tryConsume(long now) {
        refill(now);
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return Math.max(1, (long) Math.ceil((1 - tokens) * nanosPerToken));
    }

    /** A full bucket behaves exactly like a new one, so it can be dropped without changing any answer. */
    synchronized boolean isFull(long now) {
        refill(now);
        return tokens >= capacity;
    }

    private void refill(long now) {
        if (now > updatedAt) {
            tokens = Math.min(capacity, tokens + (now - updatedAt) / nanosPerToken);
            updatedAt = now;
        }
    }
}
