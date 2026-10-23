package com.pm.filmfuse.movie.seed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Opens a TMDB export file. {@link #http()} downloads it (streamed, never buffered whole); tests pass
 * one that serves an in-memory gzip.
 */
@FunctionalInterface
public interface ExportFetcher {

    /**
     * @return the raw (still gzip-compressed) body, or empty when the file does not exist (404 - it is
     *         published around 08:00 UTC, so today's may not be there yet)
     * @throws IOException any other failure (non-200 status, connection, timeout)
     */
    Optional<InputStream> open(URI uri) throws IOException;

    Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    static ExportFetcher http() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(RESPONSE_TIMEOUT).GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while requesting " + uri, ex);
            }
            int status = response.statusCode();
            if (status == 200) {
                return Optional.of(response.body());
            }
            response.body().close();
            // TMDB's file host answers 403 (S3-style) as well as 404 for a file that is not there yet.
            if (status == 404 || status == 403) {
                return Optional.empty();
            }
            throw new IOException("GET " + uri + " returned " + status);
        };
    }
}
