package com.pm.filmfuse.admin.dto;

import com.pm.filmfuse.moderation.dto.ReportReason;
import com.pm.filmfuse.moderation.dto.ReportTargetType;
import java.time.Instant;
import java.util.UUID;

/**
 * A report in the moderation queue. {@code target} previews what was reported (null fields once the
 * target is gone - reports outlive their target). {@code openReportsOnTarget} counts every OPEN report
 * on the same target, this one included. Mirrored by hand in client/types/api.ts.
 */
public record AdminReportDTO(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        String details,
        ReportStatus status,
        Instant createdAt,
        AdminUserRefDTO reporter,
        ReportTargetPreviewDTO target,
        int openReportsOnTarget,
        Instant resolvedAt,
        AdminUserRefDTO resolvedBy,
        String resolutionNote) {
}
