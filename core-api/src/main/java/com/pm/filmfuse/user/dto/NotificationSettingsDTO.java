package com.pm.filmfuse.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Which emails the caller gets. Request and response of {@code /me/settings/notifications}; a PUT
 * replaces all five, so each is required. Defaults (never saved): all true except
 * {@code weeklyDigest}. Boxed only because Jackson 3 refuses a missing primitive. Mirrored by hand
 * in client/types/api.ts.
 */
public record NotificationSettingsDTO(
        @NotNull(message = "Required") Boolean friendRequests,
        @NotNull(message = "Required") Boolean groupInvites,
        @NotNull(message = "Required") Boolean sessionStarted,
        @NotNull(message = "Required") Boolean comments,
        @NotNull(message = "Required") Boolean weeklyDigest) {

    public static final NotificationSettingsDTO DEFAULTS = new NotificationSettingsDTO(true, true, true, true, false);
}
