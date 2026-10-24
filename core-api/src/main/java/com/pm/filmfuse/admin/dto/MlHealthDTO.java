package com.pm.filmfuse.admin.dto;

/**
 * python-ml-service's {@code GET /health}. {@code configured} is false when {@code ML_SERVICE_URL} is
 * blank (the fallback ranker serves everything); {@code reachable} is true for a 2xx within the
 * timeout; {@code status} is the body's {@code status}; {@code modelVersion} is the body's
 * {@code model_version} / {@code modelVersion} when the service exposes one (null otherwise);
 * {@code latencyMs} is null when it was not called. Mirrored by hand in client/types/api.ts.
 */
public record MlHealthDTO(boolean configured, boolean reachable, String status, String modelVersion, Long latencyMs) {
}
