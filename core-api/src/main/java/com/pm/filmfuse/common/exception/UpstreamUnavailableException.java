package com.pm.filmfuse.common.exception;

/**
 * A third-party service we depend on (TMDB, Supabase Storage) failed or timed out. Maps to 502.
 * The message is written for the caller and returned verbatim, so it must never quote the upstream
 * response; put that in the cause for the log.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
