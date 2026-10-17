package com.pm.filmfuse.user.dto;

/** A stated mood preference: a slug from {@code GET /moods} and a 1-10 score. Mirrored by hand in client/types/api.ts. */
public record MoodPrefDTO(String moodTag, Integer score) {
}
