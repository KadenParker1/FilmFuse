package com.pm.filmfuse.movie.repository;

import com.pm.filmfuse.movie.model.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Integer>, MovieUpsertRepository {
}
