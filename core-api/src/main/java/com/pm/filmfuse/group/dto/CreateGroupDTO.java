package com.pm.filmfuse.group.dto;

import com.pm.filmfuse.common.validation.SafeText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /groups}. {@code name} is trimmed (1-40 characters); a blank {@code emoji} /
 * {@code color} is treated as none. {@code memberIds} (0-19, null = none) are invited; each must be
 * the caller's friend and neither may have blocked the other. The caller's own id and duplicates
 * are ignored. Mirrored in client/types/api.ts.
 */
public record CreateGroupDTO(
        @NotBlank(message = "Name is required")
        @Size(max = 40, message = "Name cannot exceed 40 characters")
        @SafeText
        String name,

        @Size(max = 8, message = "Emoji cannot exceed 8 characters")
        @SafeText
        String emoji,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must look like #RRGGBB")
        String color,

        @Size(max = 19, message = "A group can invite at most 19 people")
        List<@NotNull(message = "Member ids cannot be null") UUID> memberIds) {

    public CreateGroupDTO {
        name = name == null ? null : name.trim();
        emoji = emoji == null || emoji.isBlank() ? null : emoji.trim();
        color = color == null || color.isBlank() ? null : color.trim();
        memberIds = memberIds == null ? List.of() : memberIds;
    }
}
