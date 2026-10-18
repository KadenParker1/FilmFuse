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
 * <p>Errors: 404 becomes {@link NotFoundException}; 401 (token rejected) becomes
 * {@link CatalogAuthenticationException}, a 502 like the rest; any other error status, a timeout or an
 * unreadable body becomes {@link UpstreamUnavailableException} (502) with a fixed message - TMDB's
 * own error text never reaches the caller.
 */
public class TmdbMovieCatalogClient implements MovieCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbMovieCatalogClient.class);

    static final String UNAVAILABLE = "Movie data is temporarily unavailable.";
    static final String NOT_FOUND = "Movie not found.";
    private static final String LANGUAGE = "en-US";
    /** Details also carry the US certification (release_dates) and keywords, in the same request. */
    static final String DETAILS_APPEND = "release_dates,keywords";
    private static final String US = "US";
    /** TMDB release type 3 = theatrical. */
    private static final int THEATRICAL = 3;

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
                    IllegalStateException cause = new IllegalStateException(
                            "TMDB " + request.getMethod() + " " + request.getURI().getPath()
                                    + " returned " + response.getStatusCode().value());
                    if (response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                        log.error("TMDB rejected the access token (401); check TMDB_ACCESS_TOKEN");
                        throw new CatalogAuthenticationException(UNAVAILABLE, cause);
                    }
                    throw new UpstreamUnavailableException(UNAVAILABLE, cause);
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
                .uri(uri -> uri.path("/movie/{id}")
                        .queryParam("language", LANGUAGE)
                        .queryParam("append_to_response", DETAILS_APPEND)
                        .build(id))
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbKeyword(int id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbKeywords(List<TmdbKeyword> keywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbReleaseDate(String certification, Integer type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbCountryReleases(
            @JsonProperty("iso_3166_1") String country,
            @JsonProperty("release_dates") List<TmdbReleaseDate> releaseDates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TmdbReleaseDates(List<TmdbCountryReleases> results) {
    }

    /**
     * The US certification: the first non-empty one on a theatrical release, else the first
     * non-empty one on any release type. Null when there is none (CatalogMovie also drops values
     * outside G / PG / PG-13 / R / NC-17, such as "NR").
     */
    static String usCertification(TmdbReleaseDates releaseDates) {
        if (releaseDates == null || releaseDates.results() == null) {
            return null;
        }
        List<TmdbReleaseDate> us = releaseDates.results().stream()
                .filter(country -> country != null && US.equals(country.country()) && country.releaseDates() != null)
                .flatMap(country -> country.releaseDates().stream())
                .filter(release -> release != null && release.certification() != null
                        && !release.certification().isBlank())
                .toList();
        return us.stream()
                .filter(release -> release.type() != null && release.type() == THEATRICAL)
                .map(TmdbReleaseDate::certification)
                .findFirst()
                .orElse(us.isEmpty() ? null : us.getFirst().certification());
    }

    /**
     * List results carry {@code genre_ids}; the details endpoint carries {@code genres},
     * {@code runtime} and (appended) {@code release_dates} and {@code keywords}.
     */
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
            @JsonProperty("vote_average") Double voteAverage,
            @JsonProperty("release_dates") TmdbReleaseDates releaseDates,
            TmdbKeywords keywords) {

        CatalogMovie toCatalogMovie() {
            List<Integer> ids = genreIds != null ? genreIds
                    : genres != null ? genres.stream().map(TmdbGenre::id).toList() : List.of();
            List<String> keywordNames = keywords == null || keywords.keywords() == null ? List.of()
                    : keywords.keywords().stream().filter(k -> k != null && k.name() != null)
                            .map(TmdbKeyword::name).toList();
            return new CatalogMovie(id, title, yearOf(releaseDate),
                    runtime != null && runtime > 0 ? runtime : null,
                    posterPath, backdropPath, overview == null || overview.isBlank() ? null : overview,
                    ids, popularity, voteAverage, usCertification(releaseDates), keywordNames);
        }
    }
}
