package com.pm.filmfuse.movie.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * TMDB v3 API, authenticated with a v4 read access token as a Bearer header. Every request asks for
 * {@code language=en-US}; list requests also send {@code include_adult=false}.
 *
 * <p>Errors: 404 becomes {@link NotFoundException}; any other error status, a timeout or an
 * unreadable body becomes {@link UpstreamUnavailableException} (502) with a fixed message - TMDB's
 * own error text never reaches the caller.
 */
public class TmdbMovieCatalogClient implements MovieCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbMovieCatalogClient.class);

    static final String UNAVAILABLE = "Movie data is temporarily unavailable.";
    static final String NOT_FOUND = "Movie not found.";
    private static final String LANGUAGE = "en-US";

    private final RestClient restClient;

    /**
     * @param builder a builder already carrying the request factory (timeouts); tests bind a
     *                {@code MockRestServiceServer} to it instead
     */
    public TmdbMovieCatalogClient(RestClient.Builder builder, String baseUrl, String accessToken) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        throw new NotFoundException(NOT_FOUND);
                    }
                    if (response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                        log.error("TMDB rejected the access token (401); check TMDB_ACCESS_TOKEN");
                    }
                    throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException(
                            "TMDB " + request.getMethod() + " " + request.getURI().getPath()
                                    + " returned " + response.getStatusCode().value()));
                })
                .build();
    }

    @Override
    public CatalogPage search(String query, int page) {
        TmdbPage body = call(() -> restClient.get()
                .uri(uri -> uri.path("/search/movie")
                        .queryParam("query", "{query}")
                        .queryParam("include_adult", false)
                        .queryParam("language", LANGUAGE)
                        .queryParam("page", page)
                        .build(query))
                .retrieve()
                .body(TmdbPage.class));
        return toPage(body, page);
    }

    @Override
    public CatalogPage popular(Integer genreId, int page) {
        TmdbPage body = call(() -> restClient.get()
                .uri(uri -> {
                    if (genreId == null) {
                        uri.path("/movie/popular");
                    } else {
                        uri.path("/discover/movie")
                                .queryParam("sort_by", "popularity.desc")
                                .queryParam("with_genres", genreId)
                                .queryParam("include_adult", false);
                    }
                    return uri.queryParam("language", LANGUAGE).queryParam("page", page).build();
                })
                .retrieve()
                .body(TmdbPage.class));
        return toPage(body, page);
    }

    @Override
    public CatalogMovie details(int id) {
        TmdbMovie body = call(() -> restClient.get()
                .uri(uri -> uri.path("/movie/{id}").queryParam("language", LANGUAGE).build(id))
                .retrieve()
                .body(TmdbMovie.class));
        if (body == null) {
            throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException("TMDB returned no body for movie " + id));
        }
        return body.toCatalogMovie();
    }

    /** Timeouts, connection failures and undecodable bodies surface as RestClientException. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            throw new UpstreamUnavailableException(UNAVAILABLE, ex);
        }
    }

    private static CatalogPage toPage(TmdbPage body, int requestedPage) {
        if (body == null) {
            throw new UpstreamUnavailableException(UNAVAILABLE, new IllegalStateException("TMDB returned no body"));
        }
        List<CatalogMovie> movies = body.results() == null ? List.of()
                : body.results().stream().map(TmdbMovie::toCatalogMovie).toList();
        int page = body.page() > 0 ? body.page() : requestedPage;
        return new CatalogPage(movies, page, Math.max(1, body.totalPages()));
    }

    static Integer yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbPage(
            int page,
            List<TmdbMovie> results,
            @JsonProperty("total_pages") int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbGenre(int id, String name) {
    }

    /** List results carry {@code genre_ids}; the details endpoint carries {@code genres} and {@code runtime}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbMovie(
            int id,
            String title,
            @JsonProperty("release_date") String releaseDate,
            Integer runtime,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            String overview,
            @JsonProperty("genre_ids") List<Integer> genreIds,
            List<TmdbGenre> genres,
            Double popularity,
            @JsonProperty("vote_average") Double voteAverage) {

        CatalogMovie toCatalogMovie() {
            List<Integer> ids = genreIds != null ? genreIds
                    : genres != null ? genres.stream().map(TmdbGenre::id).toList() : List.of();
            return new CatalogMovie(id, title, yearOf(releaseDate),
                    runtime != null && runtime > 0 ? runtime : null,
                    posterPath, backdropPath, overview == null || overview.isBlank() ? null : overview,
                    ids, popularity, voteAverage);
        }
    }
}
