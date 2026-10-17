package com.pm.filmfuse.social.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The placeholder closeness formula and its default weights (contract section B). */
class ClosenessFormulaTest {

    private static final ClosenessProperties DEFAULTS = ClosenessProperties.defaults();
    private static final double EPS = 1e-9;

    @Test
    void defaultsAreTheDocumentedWeights() {
        assertThat(DEFAULTS.enabled()).isTrue();
        assertThat(DEFAULTS.cron()).isEqualTo("0 30 3 * * *");
        assertThat(DEFAULTS.windowDays()).isEqualTo(90);
        assertThat(DEFAULTS.base()).isEqualTo(0.4);
        assertThat(DEFAULTS.coSessionWeight()).isEqualTo(0.35);
        assertThat(DEFAULTS.coSessionSaturation()).isEqualTo(5);
        assertThat(DEFAULTS.tasteWeight()).isEqualTo(0.25);
        assertThat(DEFAULTS.neutralTaste()).isEqualTo(0.5);
    }

    @Test
    void nonsenseValuesFallBackToDefaults() {
        ClosenessProperties odd = new ClosenessProperties(false, " ", 0, null, null, 0, null, null);
        assertThat(odd.enabled()).isFalse();
        assertThat(odd.cron()).isEqualTo(ClosenessProperties.DEFAULT_CRON);
        assertThat(odd.windowDays()).isEqualTo(90);
        assertThat(odd.coSessionSaturation()).isEqualTo(5);
    }

    @Test
    void scoreFollowsTheFormula() {
        assertThat(ClosenessFormula.score(DEFAULTS, 0, 0.5)).isCloseTo(0.525, within(EPS));
        assertThat(ClosenessFormula.score(DEFAULTS, 1, 0.5)).isCloseTo(0.4 + 0.35 * 0.2 + 0.125, within(EPS));
        assertThat(ClosenessFormula.score(DEFAULTS, 2, 0.8)).isCloseTo(0.4 + 0.35 * 0.4 + 0.25 * 0.8, within(EPS));
        assertThat(ClosenessFormula.score(DEFAULTS, 5, 1)).isCloseTo(1.0, within(EPS));
        assertThat(ClosenessFormula.score(DEFAULTS, 0, 0)).isCloseTo(0.4, within(EPS));
    }

    @Test
    void coSessionsSaturateAndNegativeCountsAreZero() {
        assertThat(ClosenessFormula.score(DEFAULTS, 50, 0.5)).isEqualTo(ClosenessFormula.score(DEFAULTS, 5, 0.5));
        assertThat(ClosenessFormula.score(DEFAULTS, -3, 0.5)).isEqualTo(ClosenessFormula.score(DEFAULTS, 0, 0.5));
    }

    @Test
    void scoreIsClampedToZeroOne() {
        ClosenessProperties heavy = new ClosenessProperties(true, null, 90, 0.9, 0.5, 5, 0.5, 0.5);
        assertThat(ClosenessFormula.score(heavy, 10, 1)).isEqualTo(1.0);
        ClosenessProperties negative = new ClosenessProperties(true, null, 90, -0.5, 0.1, 5, 0.1, 0.5);
        assertThat(ClosenessFormula.score(negative, 0, 0)).isEqualTo(0.0);
    }

    @Test
    void tasteSimilarityIsCentredCosineMappedToZeroOne() {
        // Offsets from the neutral 5: {+1,+2,+3} etc.
        assertThat(ClosenessFormula.tasteSimilarity(new float[] {6, 7, 8}, new float[] {6, 7, 8}, 0.5))
                .isCloseTo(1.0, within(1e-6));
        assertThat(ClosenessFormula.tasteSimilarity(new float[] {6, 7, 8}, new float[] {7, 9, 10}, 0.5))
                .as("same direction, stronger feelings").isGreaterThan(0.99);
        assertThat(ClosenessFormula.tasteSimilarity(new float[] {9, 5}, new float[] {1, 5}, 0.5))
                .as("opposite tastes").isCloseTo(0.0, within(1e-9));
        assertThat(ClosenessFormula.tasteSimilarity(new float[] {9, 5}, new float[] {5, 9}, 0.5))
                .as("unrelated tastes").isCloseTo(0.5, within(1e-9));
        assertThat(ClosenessFormula.tasteSimilarity(new float[] {9, 9}, new float[] {9, 5}, 0.5))
                .isCloseTo((Math.sqrt(0.5) + 1) / 2, within(1e-6));
    }

    @Test
    void centringSeparatesUsersThatRawCosineCannot() {
        // Sci-fi lover vs sci-fi hater who both love drama: raw cosine of these all-positive vectors
        // is ~0.8 (mapped ~0.9); centred, their opposite sci-fi feelings pull it well below neutral.
        float[] lover = {10, 9};
        float[] hater = {1, 9};
        assertThat(ClosenessFormula.tasteSimilarity(lover, hater, 0.5)).isLessThan(0.5);
    }

    @Test
    void missingOrUnusableVectorsAreNeutral() {
        float[] v = {6, 7, 8};
        assertThat(ClosenessFormula.tasteSimilarity(null, v, 0.5)).isEqualTo(0.5);
        assertThat(ClosenessFormula.tasteSimilarity(v, null, 0.5)).isEqualTo(0.5);
        assertThat(ClosenessFormula.tasteSimilarity(null, null, 0.42)).isEqualTo(0.42);
        assertThat(ClosenessFormula.tasteSimilarity(new float[0], new float[0], 0.5)).isEqualTo(0.5);
        assertThat(ClosenessFormula.tasteSimilarity(v, new float[] {1, 2}, 0.5)).isEqualTo(0.5);
        assertThat(ClosenessFormula.tasteSimilarity(v, new float[] {5, 5, 5}, 0.5))
                .as("no opinion at all is neutral").isEqualTo(0.5);
    }
}
