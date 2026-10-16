package com.pm.filmfuse.movie.catalog;

import com.pm.filmfuse.common.http.RestClients;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the {@link MovieCatalogClient}: TMDB when {@code filmfuse.tmdb.access-token} is set, the
 * bundled fixture catalog when it is blank (local dev without a key, and always in tests).
 */
@Configuration
@EnableConfigurationProperties(TmdbProperties.class)
public class MovieCatalogConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public MovieCatalogClient movieCatalogClient(TmdbProperties properties) {
        if (!properties.hasAccessToken()) {
            return FixtureMovieCatalogClient.fromClasspath();
        }
        return new TmdbMovieCatalogClient(
                RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT), properties.baseUrl(), properties.accessToken());
    }
}
