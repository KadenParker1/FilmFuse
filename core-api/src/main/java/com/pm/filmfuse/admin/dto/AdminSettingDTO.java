package com.pm.filmfuse.admin.dto;

import java.time.Instant;

/**
 * One runtime setting as the admin portal shows it. {@code value} is the effective value (the stored
 * override, or {@code defaultValue} when none); a number for INTEGER, a boolean for BOOLEAN.
 * {@code min}/{@code max} are the accepted bounds of an INTEGER (null for BOOLEAN). Mirrored by hand
 * in client/types/api.ts.
 */
public record AdminSettingDTO(
        String key,
        String group,
        String label,
        String description,
        String unit,
        AdminSettingType type,
        Object value,
        Object defaultValue,
        boolean overridden,
        Long min,
        Long max,
        Instant updatedAt,
        AdminUserRefDTO updatedBy) {
}
