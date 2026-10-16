package com.pm.filmfuse.reco.model;

import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.reco.dto.ContentDimension;
import com.pm.filmfuse.reco.dto.ContentLevel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the filters need to know about a cached movie. {@code certification} null = unknown; a
 * dimension missing from {@code levels} = unknown level.
 */
public record MovieContent(
        int id,
        Certification certification,
        Map<ContentDimension, ContentLevel> levels,
        Integer runtime,
        Integer releaseYear,
        List<Integer> genreIds) {

    public MovieContent {
        EnumMap<ContentDimension, ContentLevel> copy = new EnumMap<>(ContentDimension.class);
        if (levels != null) {
            levels.forEach((dimension, level) -> {
                if (dimension != null && level != null) {
                    copy.put(dimension, level);
                }
            });
        }
        levels = Collections.unmodifiableMap(copy);
        genreIds = genreIds == null ? List.of() : List.copyOf(genreIds);
    }

    /** Only the comfort-relevant fields (no runtime, year or genres). */
    public static MovieContent of(int id, Certification certification, Map<ContentDimension, ContentLevel> levels) {
        return new MovieContent(id, certification, levels, null, null, List.of());
    }
}
