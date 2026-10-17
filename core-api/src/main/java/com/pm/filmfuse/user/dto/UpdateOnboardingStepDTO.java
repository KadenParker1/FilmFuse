package com.pm.filmfuse.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code PUT /me/onboarding/step}. A lower step than the stored one is ignored. Mirrored by hand in client/types/api.ts. */
public record UpdateOnboardingStepDTO(
        @NotNull(message = "Step is required")
        @Min(value = 0, message = "Must be between 0 and 8")
        @Max(value = 8, message = "Must be between 0 and 8")
        Integer step) {
}
