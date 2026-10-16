package com.pm.filmfuse.social.model;

import java.util.UUID;

/**
 * An unordered pair of distinct users in canonical order, matching {@code friendships}'
 * {@code (user_low, user_high)} and its {@code user_low < user_high} check.
 *
 * <p>The order must be Postgres's {@code uuid} order (unsigned bytewise), not
 * {@link UUID#compareTo}, which compares the two halves as <em>signed</em> longs and disagrees for
 * about half of all random ids.
 */
public record UserPair(UUID low, UUID high) {

    public UserPair {
        if (low == null || high == null) {
            throw new IllegalArgumentException("A pair needs two users");
        }
        if (compare(low, high) >= 0) {
            throw new IllegalArgumentException("A pair must be two distinct users in canonical order");
        }
    }

    public static UserPair of(UUID a, UUID b) {
        int order = compare(a, b);
        if (order == 0) {
            throw new IllegalArgumentException("A pair needs two distinct users");
        }
        return order < 0 ? new UserPair(a, b) : new UserPair(b, a);
    }

    /** The member of the pair that is not {@code user}. */
    public UUID other(UUID user) {
        if (user.equals(low)) {
            return high;
        }
        if (user.equals(high)) {
            return low;
        }
        throw new IllegalArgumentException("Not a member of this pair");
    }

    /**
     * A 64-bit key for {@code pg_advisory_xact_lock}, the same whichever way round the pair was
     * built. Collisions only make two unrelated pairs wait for each other.
     */
    public long lockKey() {
        long h = low.getMostSignificantBits() * 31 + low.getLeastSignificantBits();
        return h * 1_000_003L ^ (high.getMostSignificantBits() * 31 + high.getLeastSignificantBits());
    }

    /** Postgres uuid ordering: the 16 bytes compared unsigned, most significant first. */
    public static int compare(UUID a, UUID b) {
        int first = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return first != 0 ? first : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
