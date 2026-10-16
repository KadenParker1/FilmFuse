package com.pm.filmfuse.reco.ml;

/**
 * The ML service failed, timed out or answered with something unusable. Internal only: callers
 * fall back to {@link com.pm.filmfuse.reco.service.FallbackRanker}; it never reaches an API client.
 */
public class MlServiceException extends RuntimeException {

    public MlServiceException(String message) {
        super(message);
    }

    public MlServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
