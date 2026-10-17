package com.pm.filmfuse.reco.dto;

import java.time.Instant;
import java.util.List;

/**
 * One built vector version. {@code topGenres}: at most 3, from that version's interpretable values
 * ({@code stated} is always null here). Mirrored by hand in client/types/api.ts.
 */
public record TasteHistoryEntryDTO(
        int version,
        VectorChangeReason reason,
        String modelVersion,
        Instant createdAt,
        List<TasteDimensionDTO> topGenres) {
}
