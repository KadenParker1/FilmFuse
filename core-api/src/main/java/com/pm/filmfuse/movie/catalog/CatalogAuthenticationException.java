package com.pm.filmfuse.movie.catalog;

import com.pm.filmfuse.common.exception.UpstreamUnavailableException;

/**
 * The catalog rejected our credentials (TMDB 401: a missing, revoked or mistyped
 * {@code TMDB_ACCESS_TOKEN}). A subclass of {@link UpstreamUnavailableException}, so request paths
 * still answer 502; bulk callers such as the catalog seeder catch it to stop instead of retrying a
 * request that cannot succeed.
 */
public class CatalogAuthenticationException extends UpstreamUnavailableException {

    public CatalogAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
