package com.pm.filmfuse.social.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code filmfuse.closeness.*}: the schedule and the weights of the implicit-closeness formula
 * ({@link ClosenessFormula}). <strong>The weights are a tunable placeholder</strong> - the owner may
 * retune them or replace the formula; nothing else depends on their values.
 *
 * @param enabled             whether the daily job is scheduled (false under the test profile; tests
 *                            call {@link ClosenessJob#recomputeAll()})
 * @param cron                when it runs (Spring cron, UTC); default 03:30 every day
 * @param windowDays          co-sessions count if picked within this many days (90)
 * @param base                the constant term ({@code 0.4})
 * @param coSessionWeight     weight of the co-session term ({@code 0.35})
 * @param coSessionSaturation co-sessions at which that term is full ({@code 5})
 * @param tasteWeight         weight of the taste-similarity term ({@code 0.25})
 * @param neutralTaste        taste similarity used when either user has no vector ({@code 0.5})
 */
@ConfigurationProperties(prefix = "filmfuse.closeness")
public record ClosenessProperties(Boolean enabled, String cron, Integer windowDays, Double base, Double coSessionWeight,
                                  Integer coSessionSaturation, Double tasteWeight, Double neutralTaste) {

    public static final String DEFAULT_CRON = "0 30 3 * * *";

    public ClosenessProperties {
        enabled = enabled == null || enabled;
        cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron;
        windowDays = windowDays == null || windowDays < 1 ? 90 : windowDays;
        base = base == null ? 0.4 : base;
        coSessionWeight = coSessionWeight == null ? 0.35 : coSessionWeight;
        coSessionSaturation = coSessionSaturation == null || coSessionSaturation < 1 ? 5 : coSessionSaturation;
        tasteWeight = tasteWeight == null ? 0.25 : tasteWeight;
        neutralTaste = neutralTaste == null ? 0.5 : neutralTaste;
    }

    /** The documented defaults. */
    public static ClosenessProperties defaults() {
        return new ClosenessProperties(null, null, null, null, null, null, null, null);
    }
}
