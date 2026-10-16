package com.pm.filmfuse.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param path the {@code path} from {@link ProfilePhotoUploadDTO}; must be
 *             {@code <callerId>/<uuid>.jpg} (checked by the service, since it depends on the caller)
 */
public record ConfirmProfilePhotoRequestDTO(
        @NotBlank(message = "Path is required")
        @Size(max = 200, message = "Path cannot exceed 200 characters")
        String path) {
}
