package com.pm.filmfuse.reco.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A small in-memory cache for {@link PersonalRecommender}: at most {@code capacity} users,
 * least-recently-used evicted first, and every entry expires {@code ttl} after it was stored. A
 * synchronized access-ordered {@link LinkedHashMap} - no extra dependency; the critical sections are
 * a map lookup or insert. Single replica only; lost on restart (cheap to recompute).
 */
final class RecommendationCache<V> {

    private record Slot<V>(V value, Instant storedAt) {
    }

    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<UUID, Slot<V>> map;

    RecommendationCache(int capacity, Duration ttl, Clock clock) {
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Slot<V>> eldest) {
                return size() > RecommendationCache.this.capacity;
            }
        };
    }

    /** The live value for {@code userId} (marking it recently used), or null when absent or expired. */
    synchronized V get(UUID userId) {
        Slot<V> entry = map.get(userId);
        if (entry == null) {
            return null;
        }
        if (!clock.instant().isBefore(entry.storedAt().plus(ttl))) {
            map.remove(userId);
            return null;
        }
        return entry.value();
    }

    synchronized void put(UUID userId, V value) {
        map.put(userId, new Slot<>(value, clock.instant()));
    }

    synchronized void remove(UUID userId) {
        map.remove(userId);
    }

    synchronized int size() {
        return map.size();
    }
}
