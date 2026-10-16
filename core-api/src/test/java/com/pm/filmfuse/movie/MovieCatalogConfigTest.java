package com.pm.filmfuse.movie;

import com.pm.filmfuse.movie.catalog.FixtureMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.MovieCatalogConfig;
import com.pm.filmfuse.movie.catalog.TmdbMovieCatalogClient;
import com.pm.filmfuse.movie.catalog.TmdbProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MovieCatalogConfigTest {

    private final MovieCatalogConfig config = new MovieCatalogConfig();

    @Test
    void blankTokenSelectsFixtureCatalog() {
        assertThat(config.movieCatalogClient(new TmdbProperties("", null, null)))
                .isInstanceOf(FixtureMovieCatalogClient.class);
        assertThat(config.movieCatalogClient(new TmdbProperties(null, null, null)))
                .isInstanceOf(FixtureMovieCatalogClient.class);
        assertThat(config.movieCatalogClient(new TmdbProperties("   ", null, null)))
                .isInstanceOf(FixtureMovieCatalogClient.class);
    }

    @Test
    void tokenSelectsTmdb() {
        assertThat(config.movieCatalogClient(new TmdbProperties("eyJ-token", null, null)))
                .isInstanceOf(TmdbMovieCatalogClient.class);
    }

    @Test
    void propertiesDefaultAndNormaliseUrls() {
        TmdbProperties defaults = new TmdbProperties(null, null, null);
        assertThat(defaults.baseUrl()).isEqualTo("https://api.themoviedb.org/3");
        assertThat(defaults.imageBaseUrl()).isEqualTo("https://image.tmdb.org/t/p/");
        assertThat(new TmdbProperties(null, null, "https://cdn.test/t/p").imageBaseUrl())
                .isEqualTo("https://cdn.test/t/p/");
    }
}
