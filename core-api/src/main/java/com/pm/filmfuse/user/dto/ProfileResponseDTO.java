package com.pm.filmfuse.user.dto;

import com.pm.filmfuse.user.model.AccountStatus;
import java.time.Instant;
import java.util.UUID;

/** Mirrored by hand in client/types/api.ts - keep them in sync. */
public record ProfileResponseDTO(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        String bio,
        AccountStatus status,
        int onboardingStep,
        boolean onboardingCompleted,
        Instant onboardingCompletedAt,
        Instant createdAt,
        Instant updatedAt) {
}
