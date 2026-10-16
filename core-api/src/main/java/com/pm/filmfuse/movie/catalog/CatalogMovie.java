package com.pm.filmfuse.movie.catalog;

import com.pm.filmfuse.reco.dto.Certification;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A movie as a {@link MovieCatalogClient} returns it - independent of TMDB's wire format. Image
 * fields are TMDB file paths ({@code /abc.jpg}), not URLs. {@code runtime}, {@code certification}
 * and {@code keywords} come only from {@link MovieCatalogClient#details}; list endpoints (search /
 * popular) leave them null / empty.
 *
 * @param certification the US certification label ("G", "PG", "PG-13", "R", "NC-17"); anything
 *                      else (e.g. "NR") is normalised to null = unknown
 * @param keywords      TMDB keyword names, trimmed, lowercased and de-duplicated
 */
public record CatalogMovie(
        int id,
        String title,
        Integer releaseYear,
        Integer runtime,
        String posterPath,
        String backdropPath,
        String overview,
        List<Integer> genreIds,
        Double popularity,
        Double voteAverage,
        String certification,
        List<String> keywords) {

    public CatalogMovie {
        genreIds = genreIds == null ? List.of() : List.copyOf(genreIds);
        Certification known = Certification.fromLabel(certification);
        certification = known == null ? null : known.label();
        keywords = keywords == null ? List.of() : List.copyOf(new LinkedHashSet<>(keywords.stream()
                .filter(Objects::nonNull)
                .map(keyword -> keyword.trim().toLowerCase(Locale.ROOT))
                .filter(keyword -> !keyword.isEmpty())
                .toList()));
    }

    /** A movie without content data (as list endpoints return it, plus whatever runtime is given). */
    public CatalogMovie(int id, String title, Integer releaseYear, Integer runtime, String posterPath,
                        String backdropPath, String overview, List<Integer> genreIds, Double popularity,
                        Double voteAverage) {
        this(id, title, releaseYear, runtime, posterPath, backdropPath, overview, genreIds, popularity,
                voteAverage, null, List.of());
    }

    /** The same movie as a list endpoint would return it: no runtime, certification or keywords. */
    public CatalogMovie asListResult() {
        return new CatalogMovie(id, title, releaseYear, null, posterPath, backdropPath, overview, genreIds,
                popularity, voteAverage, null, List.of());
    }

    /** True when this came from a details fetch that carried content data worth deriving levels from. */
    public boolean hasContentData() {
        return certification != null || !keywords.isEmpty();
    }
}
