package com.pm.filmfuse.group.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /groups/{id}} (owner only). Every field is optional and null means "unchanged".
 * {@code name} is trimmed and must stay 1-40 characters. An empty {@code emoji} or {@code color}
 * clears it. {@code comfortCap} replaces the whole cap ({@code {"maxCertification": null,
 * "contentLimits": {}}} removes it). Mirrored in client/types/api.ts.
 */
public record UpdateGroupDTO(
        @Size(min = 1, max = 40, message = "Name must be 1-40 characters")
        String name,

        @Size(max = 8, message = "Emoji cannot exceed 8 characters")
        String emoji,

        @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "Color must look like #RRGGBB")
        String color,

        ComfortCapDTO comfortCap) {

    public UpdateGroupDTO {
        name = name == null ? null : name.trim();
        emoji = emoji == null ? null : emoji.trim();
        color = color == null ? null : color.trim();
    }
}
