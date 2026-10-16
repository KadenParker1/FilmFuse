package com.pm.filmfuse.movie.repository;

import com.pm.filmfuse.movie.model.Genre;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenreRepository extends JpaRepository<Genre, Integer> {
}
