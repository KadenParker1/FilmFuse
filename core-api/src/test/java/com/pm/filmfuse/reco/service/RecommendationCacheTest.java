package com.pm.filmfuse.reco.service;

import com.pm.filmfuse.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The For-you cache: bounded, least recently used evicted first, entries expire after the TTL. */
class RecommendationCacheTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void evictsTheLeastRecentlyUsedBeyondCapacity() {
        RecommendationCache<String> cache = new RecommendationCache<>(2, Duration.ofMinutes(30), clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        cache.put(a, "a");
        cache.put(b, "b");
        assertThat(cache.get(a)).isEqualTo("a"); // a is now the most recently used
        cache.put(c, "c");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get(b)).isNull();
        assertThat(cache.get(a)).isEqualTo("a");
        assertThat(cache.get(c)).isEqualTo("c");
    }

    @Test
    void entriesExpireAfterTheTtl() {
        RecommendationCache<String> cache = new RecommendationCache<>(10, Duration.ofMinutes(30), clock);
        UUID a = UUID.randomUUID();
        cache.put(a, "a");
        clock.advance(Duration.ofMinutes(29));
        assertThat(cache.get(a)).isEqualTo("a");
        clock.advance(Duration.ofMinutes(1));
        assertThat(cache.get(a)).isNull();
        assertThat(cache.size()).isZero();
        // A fresh put restarts the clock.
        cache.put(a, "a2");
        assertThat(cache.get(a)).isEqualTo("a2");
    }

    @Test
    void personalRecommenderUsesAThousandUsersAndThirtyMinutes() {
        assertThat(PersonalRecommender.MAX_CACHED_USERS).isEqualTo(1_000);
        assertThat(PersonalRecommender.CACHE_TTL).isEqualTo(Duration.ofMinutes(30));
        RecommendationCache<Integer> cache = new RecommendationCache<>(PersonalRecommender.MAX_CACHED_USERS,
                PersonalRecommender.CACHE_TTL, clock);
        UUID first = UUID.randomUUID();
        cache.put(first, 0);
        for (int i = 1; i <= 1_000; i++) {
            cache.put(UUID.randomUUID(), i);
        }
        assertThat(cache.size()).isEqualTo(1_000);
        assertThat(cache.get(first)).isNull();
    }
}
