package com.pm.filmfuse.social.service;

/**
 * <strong>Placeholder</strong> implicit closeness between two friends (symmetric, 0-1):
 *
 * <pre>
 * score = clamp(base + coSessionWeight * min(1, coSessions / coSessionSaturation)
 *                    + tasteWeight * tasteSimilarity, 0, 1)
 * </pre>
 *
 * with the defaults {@code 0.4 + 0.35 * min(1, coSessions90d / 5) + 0.25 * tasteSimilarity}.
 * {@code tasteSimilarity} is the cosine of the two users' interpretable vectors, each first centred
 * on the neutral score ({@value #NEUTRAL_SCORE}) so "likes" and "dislikes" point in opposite
 * directions, mapped to [0, 1] ({@code (cos + 1) / 2}); {@code neutralTaste} (0.5) when either has no
 * vector or no opinion at all (every dimension neutral). Without centring, all-positive 1-10 vectors
 * have a cosine near 1 for everyone. Weights come from {@link ClosenessProperties}; the owner may
 * retune or replace all of this.
 */
public final class ClosenessFormula {

    /** Neutral point of the 1-10 interpretable scale. */
    static final double NEUTRAL_SCORE = 5.0;

    private ClosenessFormula() {
    }

    public static double score(ClosenessProperties weights, int coSessions, double tasteSimilarity) {
        double sessions = Math.min(1.0, Math.max(0, coSessions) / (double) weights.coSessionSaturation());
        double raw = weights.base() + weights.coSessionWeight() * sessions + weights.tasteWeight() * tasteSimilarity;
        return Math.max(0, Math.min(1, raw));
    }

    /**
     * {@code (cos(a - 5, b - 5) + 1) / 2}, or {@code neutral} when either vector is missing, empty,
     * entirely neutral, or the two have different lengths (built by different model versions).
     */
    public static double tasteSimilarity(float[] a, float[] b, double neutral) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return neutral;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i] - NEUTRAL_SCORE;
            double y = b[i] - NEUTRAL_SCORE;
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0 || normB == 0) {
            return neutral;
        }
        double cos = Math.max(-1, Math.min(1, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
        return (cos + 1) / 2;
    }
}
