package com.pm.filmfuse.admin.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A tiny time-based cache for computed metrics blocks (a handful of keys, so no eviction beyond the
 * TTL). Two admins loading the overview at once may both compute a block; that is harmless.
 * A zero TTL disables caching.
 */
public class MetricsCache {

    private record Entry(Object value, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public MetricsCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> compute) {
        if (ttl.isZero()) {
            return compute.get();
        }
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return (T) entry.value();
        }
        T value = compute.get();
        entries.put(key, new Entry(value, now.plus(ttl)));
        return value;
    }
}
