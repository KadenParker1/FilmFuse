package com.pm.filmfuse.moderation.dto;

/** What a report is about; {@code targetId} is that thing's id. Mirrored as a string union in client/types/api.ts. */
public enum ReportTargetType {
    RATING,
    COMMENT,
    USER
}
