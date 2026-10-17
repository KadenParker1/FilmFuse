package com.pm.filmfuse.moderation.dto;

import com.pm.filmfuse.common.validation.SafeText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /reports}. {@code targetId} is the rating, comment or user id (a UUID);
 * {@code details} is optional free text, trimmed, at most 500 characters. Mirrored by hand in
 * client/types/api.ts.
 */
public record CreateReportDTO(
        @NotNull(message = "Required")
        ReportTargetType targetType,

        @NotBlank(message = "Required")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "Must be a valid id")
        String targetId,

        @NotNull(message = "Required")
        ReportReason reason,

        @Size(max = 500, message = "Details cannot exceed 500 characters")
        @SafeText(multiline = true)
        String details) {

    public CreateReportDTO {
        targetId = targetId == null ? null : targetId.strip();
        details = details == null || details.isBlank() ? null : details.replace("\r\n", "\n").strip();
    }
}
