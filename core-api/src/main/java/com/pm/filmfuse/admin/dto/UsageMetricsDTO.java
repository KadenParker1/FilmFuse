package com.pm.filmfuse.admin.dto;

import java.time.Instant;
import java.util.List;

/**
 * Usage. {@code ratingsPerDay} has one entry per UTC day for the last 30 days, oldest first, zero-filled
 * (every rating source counts). Sessions: started = created in the window, picked = decided in the
 * window. {@code activeGroups30d} = groups with a session started in the last 30 days. Mirrored by
 * hand in client/types/api.ts.
 */
public record UsageMetricsDTO(
        long usersTotal,
        long usersNew7d,
        long usersOnboarded,
        List<DailyCountDTO> ratingsPerDay,
        long sessionsStarted7d,
        long sessionsStarted30d,
        long sessionsPicked7d,
        long sessionsPicked30d,
        long activeGroups30d,
        Instant generatedAt) {
}
