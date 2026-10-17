package com.pm.filmfuse.common.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.rate-limit.*}.
 *
 * @param enabled    off under the test profile (dedicated tests switch it on)
 * @param maxBuckets hard cap on buckets held in memory (users + IPs + categories)
 * @param limits     overrides by {@link RateLimitRule#key()}, e.g. {@code limits.comments.capacity=60}
 *                   and {@code limits.comments.period=PT1H}; anything left out keeps the rule's default
 */
@ConfigurationProperties(prefix = "filmfuse.rate-limit")
public record RateLimitProperties(Boolean enabled, Integer maxBuckets, Map<String, Limit> limits) {

    public static final int DEFAULT_MAX_BUCKETS = 100_000;

    /** {@code capacity} requests per {@code period}, refilled continuously. */
    public record Limit(Integer capacity, Duration period) {
    }

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        maxBuckets = maxBuckets == null ? DEFAULT_MAX_BUCKETS : maxBuckets;
        if (maxBuckets < 1) {
            throw new IllegalStateException("filmfuse.rate-limit.max-buckets must be positive");
        }
        limits = limits == null ? Map.of() : Map.copyOf(limits);
        for (String name : limits.keySet()) {
            if (java.util.Arrays.stream(RateLimitRule.values()).noneMatch(rule -> rule.key().equals(name))) {
                throw new IllegalStateException("Unknown rate limit filmfuse.rate-limit.limits." + name);
            }
        }
    }

    /** Every rule's effective limit: the override where given, the default otherwise. */
    public Map<RateLimitRule, Limit> resolved() {
        Map<RateLimitRule, Limit> result = new EnumMap<>(RateLimitRule.class);
        for (RateLimitRule rule : RateLimitRule.values()) {
            Limit override = limits.get(rule.key());
            int capacity = override != null && override.capacity() != null ? override.capacity() : rule.defaultCapacity();
            Duration period = override != null && override.period() != null ? override.period() : rule.defaultPeriod();
            if (capacity < 1 || period.isNegative() || period.isZero()) {
                throw new IllegalStateException("filmfuse.rate-limit.limits." + rule.key()
                        + " needs a positive capacity and period");
            }
            result.put(rule, new Limit(capacity, period));
        }
        return result;
    }
}
