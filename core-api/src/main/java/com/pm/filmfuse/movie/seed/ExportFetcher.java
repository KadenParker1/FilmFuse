package com.pm.filmfuse.movie.seed;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Locale;
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
    /**
     * The longest wait for the response headers or for any further body bytes. A socket read timeout,
     * so a download that stalls mid-body fails instead of hanging the seeder (a request timeout of
     * {@code java.net.http.HttpClient} only covers the headers).
     */
    Duration READ_TIMEOUT = Duration.ofSeconds(30);

    static ExportFetcher http() {
        return http(CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    static ExportFetcher http(Duration connectTimeout, Duration readTimeout) {
        return uri -> {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new IOException("unsupported export URL scheme: " + uri);
            }
            URLConnection opened = uri.toURL().openConnection();
            if (!(opened instanceof HttpURLConnection connection)) {
                throw new IOException("not an HTTP URL: " + uri);
            }
            connection.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
            connection.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
            // Same-protocol redirects only: HttpURLConnection never follows https -> http.
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException ex) {
                connection.disconnect();
                throw ex;
            }
            if (status == 200) {
                return Optional.of(new FilterInputStream(connection.getInputStream()) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            connection.disconnect();
                        }
                    }
                });
            }
            InputStream error = connection.getErrorStream();
            if (error != null) {
                error.close();
            }
            connection.disconnect();
            // TMDB's file host answers 403 (S3-style) as well as 404 for a file that is not there yet.
            if (status == 404 || status == 403) {
                return Optional.empty();
            }
            throw new IOException("GET " + uri + " returned " + status);
        };
    }
}
