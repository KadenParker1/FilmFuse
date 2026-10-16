package com.pm.filmfuse.reco.ml;

import java.util.List;
import java.util.Map;

/**
 * ML contract {@code Filters}. All hard constraints. {@code maxCertification} is a label ("PG-13");
 * {@code contentLimits} maps lowercase dimension to lowercase level and lists only limited
 * dimensions. The contract's {@code maxContentRating} / {@code streamingServices} are not sent
 * (their defaults apply).
 */
public record MlFilters(
        Integer maxRuntime,
        Integer minYear,
        List<Integer> genreIds,
        String maxCertification,
        Map<String, String> contentLimits,
        boolean allowUnrated) {

    public MlFilters {
        contentLimits = contentLimits == null ? Map.of() : contentLimits;
    }
}
