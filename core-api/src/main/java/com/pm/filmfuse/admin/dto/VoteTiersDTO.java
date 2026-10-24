package com.pm.filmfuse.admin.dto;

/** Movies per TMDB vote-count bin (exclusive bins). Mirrored by hand in client/types/api.ts. */
public record VoteTiersDTO(long votes1000Plus, long votes100To999, long votes20To99, long votesUnder20,
                           long votesUnknown) {
}
