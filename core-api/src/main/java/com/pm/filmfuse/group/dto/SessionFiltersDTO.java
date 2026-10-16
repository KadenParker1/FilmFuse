package com.pm.filmfuse.group.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A session's own filters, all optional and all hard: at most {@code maxRuntime} minutes (40-400),
 * released in {@code minYear} or later (1900-2100), in at least one of {@code genreIds}. A movie
 * whose runtime or year is not known yet is not excluded by those two. Mirrored in
 * client/types/api.ts.
 */
public record SessionFiltersDTO(
        @Min(value = 40, message = "Must be between 40 and 400")
        @Max(value = 400, message = "Must be between 40 and 400")
        Integer maxRuntime,

        @Min(value = 1900, message = "Must be between 1900 and 2100")
        @Max(value = 2100, message = "Must be between 1900 and 2100")
        Integer minYear,

        @Size(max = 20, message = "At most 20 genres")
        List<@NotNull(message = "Genre ids cannot be null") Integer> genreIds) {

    public static final SessionFiltersDTO NONE = new SessionFiltersDTO(null, null, null);
}
