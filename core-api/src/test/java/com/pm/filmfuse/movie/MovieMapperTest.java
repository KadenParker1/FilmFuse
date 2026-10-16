package com.pm.filmfuse.movie;

import com.pm.filmfuse.movie.catalog.CatalogMovie;
import com.pm.filmfuse.movie.dto.GenreDTO;
import com.pm.filmfuse.movie.dto.MovieDetailDTO;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.mapper.MovieMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MovieMapperTest {

    private static final String IMAGES = "https://image.tmdb.org/t/p/";

    @Test
    void imageUrls() {
        assertThat(MovieMapper.imageUrl(IMAGES, "w500", "/abc.jpg")).isEqualTo("https://image.tmdb.org/t/p/w500/abc.jpg");
        assertThat(MovieMapper.imageUrl(IMAGES, "w1280", "abc.jpg")).isEqualTo("https://image.tmdb.org/t/p/w1280/abc.jpg");
        assertThat(MovieMapper.imageUrl(IMAGES, "w500", null)).isNull();
        assertThat(MovieMapper.imageUrl(IMAGES, "w500", " ")).isNull();
    }

    @Test
    void summaryUsesW500Poster() {
        CatalogMovie movie = new CatalogMovie(603, "The Matrix", 1999, null, "/p.jpg", "/b.jpg", "Neo.",
                List.of(28, 878), 99.0, 8.2);

        MovieSummaryDTO dto = MovieMapper.toSummaryDTO(movie, IMAGES);

        assertThat(dto).isEqualTo(new MovieSummaryDTO(603, "The Matrix", 1999,
                "https://image.tmdb.org/t/p/w500/p.jpg", List.of(28, 878), 8.2));
    }

    @Test
    void summaryWithoutPosterHasNullUrl() {
        CatalogMovie movie = new CatalogMovie(1, "No Art", null, null, null, null, null, null, null, null);

        MovieSummaryDTO dto = MovieMapper.toSummaryDTO(movie, IMAGES);

        assertThat(dto.posterUrl()).isNull();
        assertThat(dto.releaseYear()).isNull();
        assertThat(dto.genreIds()).isEmpty();
        assertThat(dto.voteAverage()).isNull();
    }

    @Test
    void detailUsesW1280BackdropAndNamesGenresInOrderSkippingUnknownIds() {
        CatalogMovie movie = new CatalogMovie(155, "The Dark Knight", 2008, 152, "/p.jpg", "/b.jpg", "Joker.",
                List.of(18, 4242, 28), 120.0, 8.5);

        MovieDetailDTO dto = MovieMapper.toDetailDTO(movie, Map.of(18, "Drama", 28, "Action"), "https://cdn.test/");

        assertThat(dto.posterUrl()).isEqualTo("https://cdn.test/w500/p.jpg");
        assertThat(dto.backdropUrl()).isEqualTo("https://cdn.test/w1280/b.jpg");
        assertThat(dto.genres()).containsExactly(new GenreDTO(18, "Drama"), new GenreDTO(28, "Action"));
        assertThat(dto.runtime()).isEqualTo(152);
        assertThat(dto.popularity()).isEqualTo(120.0);
    }

    @Test
    void detailWithoutImagesHasNullUrls() {
        CatalogMovie movie = new CatalogMovie(2, "Bare", 2000, 90, null, null, null, List.of(), null, null);

        MovieDetailDTO dto = MovieMapper.toDetailDTO(movie, Map.of(), IMAGES);

        assertThat(dto.posterUrl()).isNull();
        assertThat(dto.backdropUrl()).isNull();
        assertThat(dto.overview()).isNull();
        assertThat(dto.genres()).isEmpty();
    }
}
