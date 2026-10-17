package com.pm.filmfuse.moderation.dto;

/** Why something was reported. Mirrored as a string union in client/types/api.ts. */
public enum ReportReason {
    SPAM,
    HARASSMENT,
    HATE,
    SEXUAL_CONTENT,
    SPOILERS,
    OTHER
}
