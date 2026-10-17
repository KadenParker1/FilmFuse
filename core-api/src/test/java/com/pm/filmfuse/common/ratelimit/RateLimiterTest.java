package com.pm.filmfuse.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pm.filmfuse.support.MutableClock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock();

    private RateLimiter limiter(int maxBuckets, Map<String, RateLimitProperties.Limit> limits) {
        return new RateLimiter(new RateLimitProperties(true, maxBuckets, limits), clock);
    }

    private RateLimiter commentsLimit(int capacity, Duration period) {
        return limiter(1000, Map.of("comments", new RateLimitProperties.Limit(capacity, period)));
    }

    @Test
    void allowsUpToCapacityThenRefusesWithTheWaitForOneToken() {
        RateLimiter limiter = commentsLimit(3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).isEmpty();
        }
        Optional<Duration> wait = limiter.tryAcquire(RateLimitRule.COMMENTS, "u");
        assertThat(wait).isPresent();
        // One token per 20 minutes; a few real milliseconds may have passed.
        assertThat(wait.get()).isBetween(Duration.ofMinutes(19), Duration.ofMinutes(20));
    }

    @Test
    void refillsOverTimeAndResetsCompletelyAfterThePeriod() {
        RateLimiter limiter = commentsLimit(3, Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(RateLimitRule.COMMENTS, "u");
        }
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).isPresent();

        clock.advance(Duration.ofMinutes(20));
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).as("one token back").isEmpty();
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).isPresent();

        clock.advance(Duration.ofHours(2));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).as("full again, never above capacity").isEmpty();
        }
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "u")).isPresent();
    }

    @Test
    void subjectsAndRulesHaveSeparateBuckets() {
        RateLimiter limiter = limiter(1000, Map.of(
                "comments", new RateLimitProperties.Limit(1, Duration.ofHours(1)),
                "reports", new RateLimitProperties.Limit(1, Duration.ofHours(1))));
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "a")).isEmpty();
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "a")).isPresent();
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "b")).as("another user").isEmpty();
        assertThat(limiter.tryAcquire(RateLimitRule.REPORTS, "a")).as("another rule").isEmpty();
    }

    @Test
    void defaultsApplyWhereNothingIsOverridden() {
        RateLimiter limiter = limiter(1000, Map.of("comments", new RateLimitProperties.Limit(5, null)));
        assertThat(limiter.limitOf(RateLimitRule.COMMENTS)).isEqualTo(new RateLimitProperties.Limit(5, Duration.ofHours(1)));
        assertThat(limiter.limitOf(RateLimitRule.USER)).isEqualTo(new RateLimitProperties.Limit(120, Duration.ofMinutes(1)));
        assertThat(limiter.limitOf(RateLimitRule.IP)).isEqualTo(new RateLimitProperties.Limit(600, Duration.ofMinutes(1)));
        assertThat(limiter.limitOf(RateLimitRule.FRIEND_REQUESTS).capacity()).isEqualTo(30);
        assertThat(limiter.limitOf(RateLimitRule.INVITES).capacity()).isEqualTo(20);
        assertThat(limiter.limitOf(RateLimitRule.REPORTS).capacity()).isEqualTo(20);
        assertThat(limiter.limitOf(RateLimitRule.SESSIONS).capacity()).isEqualTo(30);
        assertThat(limiter.limitOf(RateLimitRule.PHOTO_UPLOAD).capacity()).isEqualTo(20);
    }

    @Test
    void memoryIsBoundedByMaxBuckets() {
        RateLimiter limiter = limiter(50, Map.of());
        for (int i = 0; i < 10_000; i++) {
            limiter.tryAcquire(RateLimitRule.IP, "10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(50);
    }

    @Test
    void sweepingDropsOnlyRefilledBuckets() {
        RateLimiter limiter = limiter(3, Map.of("comments", new RateLimitProperties.Limit(1, Duration.ofHours(1))));
        limiter.tryAcquire(RateLimitRule.COMMENTS, "busy");
        clock.advance(Duration.ofMinutes(1));
        limiter.tryAcquire(RateLimitRule.USER, "idle-1");
        limiter.tryAcquire(RateLimitRule.USER, "idle-2");
        // USER buckets refill in a minute; the COMMENTS one needs an hour.
        clock.advance(Duration.ofMinutes(5));
        limiter.tryAcquire(RateLimitRule.USER, "new");
        assertThat(limiter.tryAcquire(RateLimitRule.COMMENTS, "busy")).as("still limited after the sweep").isPresent();
    }

    @Test
    void reportsItsEnabledFlag() {
        assertThat(new RateLimiter(new RateLimitProperties(false, null, null), clock).isEnabled()).isFalse();
        assertThat(new RateLimiter(new RateLimitProperties(null, null, null), clock).isEnabled()).isTrue();
    }

    @Test
    void rejectsUnknownOrInvalidLimits() {
        assertThatThrownBy(() -> new RateLimitProperties(true, 10, Map.of("nope", new RateLimitProperties.Limit(1, null))))
                .hasMessageContaining("filmfuse.rate-limit.limits.nope");
        assertThatThrownBy(() -> commentsLimit(0, Duration.ofHours(1))).hasMessageContaining("comments");
        assertThatThrownBy(() -> commentsLimit(1, Duration.ZERO)).hasMessageContaining("comments");
        assertThatThrownBy(() -> new RateLimitProperties(true, 0, null)).hasMessageContaining("max-buckets");
    }
}
