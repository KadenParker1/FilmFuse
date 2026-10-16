package com.pm.filmfuse.reco.model;

import java.util.List;

/**
 * A session's own filters (SessionFiltersDTO) as the candidate query applies them. Null / empty =
 * not filtered. A movie whose runtime or year is unknown is not excluded by them (TMDB list results
 * carry no runtime); genres must overlap.
 */
public record CandidateCriteria(Integer maxRuntime, Integer minYear, List<Integer> genreIds) {

    public static final CandidateCriteria NONE = new CandidateCriteria(null, null, List.of());

    public CandidateCriteria {
        genreIds = genreIds == null ? List.of() : List.copyOf(genreIds);
    }

    public boolean matches(MovieContent movie) {
        if (maxRuntime != null && movie.runtime() != null && movie.runtime() > maxRuntime) {
            return false;
        }
        if (minYear != null && movie.releaseYear() != null && movie.releaseYear() < minYear) {
            return false;
        }
        return genreIds.isEmpty() || movie.genreIds().stream().anyMatch(genreIds::contains);
    }
}
