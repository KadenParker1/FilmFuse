package com.pm.filmfuse.admin.dto;

/**
 * {@code PUT /admin/settings/{key}}: the new value, a JSON number for INTEGER settings and a boolean
 * for BOOLEAN ones. Type and bounds are checked against the key's definition (400 with
 * {@code fieldErrors.value}). To go back to the default, {@code DELETE} the setting. Mirrored by hand
 * in client/types/api.ts.
 */
public record UpdateAdminSettingDTO(Object value) {
}
