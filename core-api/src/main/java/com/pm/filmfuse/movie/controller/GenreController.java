package com.pm.filmfuse.movie.controller;

import com.pm.filmfuse.movie.dto.GenreDTO;
import com.pm.filmfuse.movie.service.GenreService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/genres")
public class GenreController {

    private final GenreService genreService;

    public GenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    /** The 19 TMDB movie genres, sorted by name. */
    @GetMapping
    public List<GenreDTO> list() {
        return genreService.list();
    }
}
