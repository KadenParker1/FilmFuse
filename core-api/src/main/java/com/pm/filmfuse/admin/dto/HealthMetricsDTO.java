package com.pm.filmfuse.admin.dto;

import java.time.Instant;

/**
 * Operational health. {@code vectorsDirty} = taste vectors waiting for a rebuild, {@code vectorsDirtyOldest}
 * when the longest-waiting one was marked (null when none), {@code vectorsNeverBuilt} = rows still at
 * version 0. Mirrored by hand in client/types/api.ts.
 */
public record HealthMetricsDTO(
        MlHealthDTO ml,
        long vectorsDirty,
        Instant vectorsDirtyOldest,
        long vectorsNeverBuilt,
        long openReports,
        Instant generatedAt) {
}
