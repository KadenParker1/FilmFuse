package com.pm.filmfuse.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit-log row. {@code admin} is null once that admin's account is deleted. {@code target} names
 * what was changed (a setting key, a job name, a report id); {@code oldValue}/{@code newValue} are
 * JSON values (null when there was none). Mirrored by hand in client/types/api.ts.
 */
public record AdminAuditEntryDTO(
        UUID id,
        AdminUserRefDTO admin,
        AdminAction action,
        String target,
        Object oldValue,
        Object newValue,
        Instant createdAt) {
}
