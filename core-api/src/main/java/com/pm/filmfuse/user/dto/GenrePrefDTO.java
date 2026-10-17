package com.pm.filmfuse.user.dto;

/**
 * A stated genre preference: TMDB genre id and a 1-10 score. Boxed because Jackson 3 refuses a
 * missing primitive; validated by TastePreferencesService. Mirrored by hand in client/types/api.ts.
 */
public record GenrePrefDTO(Integer genreId, Integer score) {
}
