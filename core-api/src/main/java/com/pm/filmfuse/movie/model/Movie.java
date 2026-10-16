package com.pm.filmfuse.movie.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A cached TMDB movie ({@code id} is the TMDB id). Read through JPA, written only by
 * {@link com.pm.filmfuse.movie.repository.MovieUpsertRepository} (an {@code insert ... on conflict}
 * upsert), hence {@link Immutable}.
 */
@Entity
@Immutable
@Table(name = "movies", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Movie {

    @Id
    private Integer id;

    private String title;

    @Column(name = "release_year")
    private Integer releaseYear;

    /** Null until a details fetch: TMDB list endpoints do not return it. */
    private Integer runtime;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "backdrop_path")
    private String backdropPath;

    private String overview;

    @Column(name = "genre_ids")
    private Integer[] genreIds;

    private Double popularity;

    @Column(name = "vote_average")
    private Double voteAverage;

    /** US certification label ("PG-13"); null = unknown. Only a details fetch fills it in. */
    private String certification;

    /** Lowercased TMDB keyword names; empty until a details fetch. */
    private String[] keywords;

    @Column(name = "cached_at")
    private Instant cachedAt;
}
