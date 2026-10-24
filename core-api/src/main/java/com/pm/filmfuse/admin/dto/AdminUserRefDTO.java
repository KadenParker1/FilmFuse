package com.pm.filmfuse.admin.dto;

import java.util.UUID;

/** A user as admin lists name them (no email). Mirrored by hand in client/types/api.ts. */
public record AdminUserRefDTO(UUID id, String username, String displayName) {
}
