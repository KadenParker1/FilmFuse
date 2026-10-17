package com.pm.filmfuse.user.dto;

/** A mood ("vibe") dimension: {@code tag} is the slug ("mind-bending"). Mirrored by hand in client/types/api.ts. */
public record MoodDTO(String tag, String label) {
}
