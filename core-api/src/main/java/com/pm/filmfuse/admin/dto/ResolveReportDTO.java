package com.pm.filmfuse.admin.dto;

import com.pm.filmfuse.common.validation.SafeText;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/reports/{id}/resolve}: RESOLVED (action taken) or DISMISSED (no action needed),
 * with an optional note of at most 500 characters. Mirrored by hand in client/types/api.ts.
 */
public record ResolveReportDTO(
        @NotNull(message = "Required")
        ReportResolution resolution,

        @Size(max = 500, message = "Notes cannot exceed 500 characters")
        @SafeText(multiline = true)
        String note) {

    public ResolveReportDTO {
        note = note == null || note.isBlank() ? null : note.replace("\r\n", "\n").strip();
    }
}
