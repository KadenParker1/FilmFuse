package com.pm.filmfuse.movie.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param accessToken  TMDB v4 "API Read Access Token" ({@code TMDB_ACCESS_TOKEN}); blank selects the
 *                     offline fixture catalog
 * @param baseUrl      TMDB API root, {@code https://api.themoviedb.org/3}
 * @param imageBaseUrl TMDB image CDN root, {@code https://image.tmdb.org/t/p/} (a size and the file
 *                     path are appended)
 */
@ConfigurationProperties(prefix = "filmfuse.tmdb")
public record TmdbProperties(String accessToken, String baseUrl, String imageBaseUrl) {

    public static final String DEFAULT_BASE_URL = "https://api.themoviedb.org/3";
    public static final String DEFAULT_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/";

    public TmdbProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        imageBaseUrl = imageBaseUrl == null || imageBaseUrl.isBlank() ? DEFAULT_IMAGE_BASE_URL : imageBaseUrl;
        if (!imageBaseUrl.endsWith("/")) {
            imageBaseUrl = imageBaseUrl + "/";
        }
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }
}
