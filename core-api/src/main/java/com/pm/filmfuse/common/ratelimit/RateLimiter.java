package com.pm.filmfuse.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets keyed by rule + subject (user id or client address).
 *
 * <p><strong>Single replica only.</strong> Buckets live in this JVM: with N replicas a client gets N
 * times the limit, and a restart forgets every bucket. That matches the deployment (one Railway
 * replica, see CLAUDE.md); a second replica needs a shared store (Redis / Postgres) instead.
 *
 * <p>Memory is bounded: every {@value #SWEEP_EVERY} acquisitions, and whenever the map reaches
 * {@code maxBuckets}, buckets that have refilled completely are dropped (a full bucket answers
 * exactly like a new one, so this never changes a decision). If the map is still at the cap
 * (e.g. a flood of distinct addresses), arbitrary buckets (a tenth of the cap) are evicted - that can only make a limit
 * more lenient for the evicted subject, never lock anyone out.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    static final int SWEEP_EVERY = 4096;

    private final boolean enabled;
    private final int maxBuckets;
    private final Map<RateLimitRule, RateLimitProperties.Limit> limits;
    private final Clock clock;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong acquisitions = new AtomicLong();

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.enabled = properties.enabled();
        this.maxBuckets = properties.maxBuckets();
        this.limits = properties.resolved();
        this.clock = clock;
        if (!enabled) {
            log.warn("Rate limiting is disabled (filmfuse.rate-limit.enabled=false).");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RateLimitProperties.Limit limitOf(RateLimitRule rule) {
        return limits.get(rule);
    }

    /**
     * Takes one token from {@code rule}'s bucket for {@code subject}.
     *
     * @return empty when allowed; otherwise how long until the next request would be
     */
    public Optional<Duration> tryAcquire(RateLimitRule rule, String subject) {
        long now = nanos(clock.instant());
        if (acquisitions.incrementAndGet() % SWEEP_EVERY == 0 || buckets.size() >= maxBuckets) {
            evict(now);
        }
        RateLimitProperties.Limit limit = limits.get(rule);
        TokenBucket bucket = buckets.computeIfAbsent(rule.key() + '|' + subject,
                key -> new TokenBucket(limit.capacity(), limit.period().toNanos(), now));
        long waitNanos = bucket.tryConsume(now);
        return waitNanos == 0 ? Optional.empty() : Optional.of(Duration.ofNanos(waitNanos));
    }

    /** Buckets currently held (for tests and diagnostics). */
    public int size() {
        return buckets.size();
    }

    /** Forgets every bucket (tests). */
    public void clear() {
        buckets.clear();
    }

    private void evict(long now) {
        buckets.values().removeIf(bucket -> bucket.isFull(now));
        // Still near the cap: free a tenth of it at once, so a flood of new keys pays for one sweep
        // per maxBuckets/10 insertions rather than one per request.
        Iterator<String> keys = buckets.keySet().iterator();
        int excess = buckets.size() - (maxBuckets - Math.max(1, maxBuckets / 10));
        while (excess > 0 && keys.hasNext()) {
            keys.next();
            keys.remove();
            excess--;
        }
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
