package com.pm.filmfuse.movie.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A TMDB movie genre. The 19 rows are seeded by V3 and never written by the app. */
@Entity
@Immutable
@Table(name = "genres", schema = "app")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Genre {

    /** The TMDB genre id. */
    @Id
    private Integer id;

    private String name;
}
