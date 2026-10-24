package com.pm.filmfuse.admin.dto;

/** A report's state ({@code app.reports.status}). Mirrored as a string union in client/types/api.ts. */
public enum ReportStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
