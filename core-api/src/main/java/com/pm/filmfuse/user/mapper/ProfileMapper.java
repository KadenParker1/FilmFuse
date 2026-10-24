package com.pm.filmfuse.user.mapper;

import com.pm.filmfuse.user.dto.ProfileResponseDTO;
import com.pm.filmfuse.user.dto.UpdateProfileRequestDTO;
import com.pm.filmfuse.user.model.Profile;

public final class ProfileMapper {

    private ProfileMapper() {
    }

    public static ProfileResponseDTO toDTO(Profile profile) {
        return new ProfileResponseDTO(
                profile.getId(),
                profile.getUsername(),
                profile.getDisplayName(),
                profile.getProfileImageUrl(),
                profile.getBio(),
                profile.getStatus(),
                profile.getOnboardingStep(),
                profile.getOnboardingCompletedAt() != null,
                profile.getOnboardingCompletedAt(),
                profile.getCreatedAt(),
                profile.getUpdatedAt(),
                profile.isAdmin());
    }

    /**
     * Applies the non-null fields of a PATCH. Username uniqueness is the service's job; this only
     * copies values. Blank free-text fields clear the value.
     */
    public static void applyUpdate(Profile profile, UpdateProfileRequestDTO request) {
        if (request.username() != null) {
            profile.setUsername(request.username());
        }
        if (request.displayName() != null) {
            profile.setDisplayName(blankToNull(request.displayName()));
        }
        if (request.bio() != null) {
            profile.setBio(blankToNull(request.bio()));
        }
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
