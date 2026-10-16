package com.pm.filmfuse.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * A partial profile update (PATCH): every field is optional and {@code null} means "leave it
 * alone". For displayName / bio / profileImageUrl an empty string clears the value. A username,
 * once set, can be changed but not cleared.
 *
 * <p>The username is trimmed and lowercased before validation, so "Alice" is accepted as "alice".
 */
public record UpdateProfileRequestDTO(
        @Size(min = UpdateProfileRequestDTO.USERNAME_MIN, max = UpdateProfileRequestDTO.USERNAME_MAX,
                message = "Username must be 3-30 characters")
        @Pattern(regexp = UpdateProfileRequestDTO.USERNAME_REGEX,
                message = "Username may contain only lowercase letters, digits and underscores")
        String username,

        @Size(max = 77, message = "Display name cannot exceed 77 characters")
        String displayName,

        @Size(max = 300, message = "Bio cannot exceed 300 characters")
        String bio,

        @Size(max = 1024, message = "Profile image URL cannot exceed 1024 characters")
        String profileImageUrl) {

    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 30;
    public static final String USERNAME_REGEX = "^[a-z0-9_]+$";

    public UpdateProfileRequestDTO {
        username = normaliseUsername(username);
    }

    public static String normaliseUsername(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidUsername(String normalised) {
        return normalised != null
                && normalised.length() >= USERNAME_MIN
                && normalised.length() <= USERNAME_MAX
                && normalised.matches(USERNAME_REGEX);
    }
}
