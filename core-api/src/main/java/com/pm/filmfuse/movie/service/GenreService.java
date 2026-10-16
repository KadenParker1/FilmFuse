package com.pm.filmfuse.movie.service;

import com.pm.filmfuse.movie.dto.GenreDTO;
import com.pm.filmfuse.movie.mapper.MovieMapper;
import com.pm.filmfuse.movie.model.Genre;
import com.pm.filmfuse.movie.repository.GenreRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class GenreService {

    private final GenreRepository genreRepository;

    public GenreService(GenreRepository genreRepository) {
        this.genreRepository = genreRepository;
    }

    /**
     * All 19 TMDB genres, by name. Sorted here, case-insensitively, rather than by the database:
     * {@code order by name} follows the database collation, which differs between Supabase and a
     * stock Postgres ("TV Movie" vs "Thriller").
     */
    public List<GenreDTO> list() {
        return genreRepository.findAll().stream()
                .sorted(Comparator.comparing(Genre::getName, String.CASE_INSENSITIVE_ORDER))
                .map(MovieMapper::toDTO)
                .toList();
    }

    public Map<Integer, String> namesById() {
        return genreRepository.findAll().stream().collect(Collectors.toMap(Genre::getId, Genre::getName));
    }

    public boolean exists(int genreId) {
        return genreRepository.existsById(genreId);
    }
}
