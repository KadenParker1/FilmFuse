package com.pm.filmfuse.reco.ml;

import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls python-ml-service's public {@code GET /health} (no contract change: it has always existed) for
 * the admin portal. Same base URL and key as {@link MlServiceClient}, but its own short timeouts, so a
 * hung service never stalls an admin page for the full 5 s.
 */
public class MlHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(MlHealthProbe.class);
    static final String HEALTH = "/health";

    /**
     * @param reachable    a 2xx answer within the timeout
     * @param status       the body's {@code status} (e.g. "ok"), if any
     * @param modelVersion the body's {@code model_version} / {@code modelVersion}, if the service exposes one
     * @param latencyMs    how long the call took; null when it was not made (not configured)
     */
    public record MlHealth(boolean configured, boolean reachable, String status, String modelVersion, Long latencyMs) {
    }

    private final RestClient restClient;
    private final boolean configured;
    private final Clock clock;

    public MlHealthProbe(RestClient.Builder builder, MlProperties properties, Clock clock) {
        this.configured = properties.isConfigured();
        if (configured) {
            builder.baseUrl(properties.baseUrl());
        }
        if (!properties.internalKey().isBlank()) {
            builder.defaultHeader(MlServiceClient.INTERNAL_KEY_HEADER, properties.internalKey());
        }
        this.restClient = builder.build();
        this.clock = clock;
    }

    public MlHealth check() {
        if (!configured) {
            return new MlHealth(false, false, null, null, null);
        }
        long started = clock.millis();
        try {
            Map<?, ?> body = restClient.get().uri(HEALTH).accept(MediaType.APPLICATION_JSON).retrieve().body(Map.class);
            long latency = Math.max(0, clock.millis() - started);
            return new MlHealth(true, true, text(body, "status"), modelVersion(body), latency);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("ML health check failed: {}", ex.getClass().getSimpleName());
            return new MlHealth(true, false, null, null, Math.max(0, clock.millis() - started));
        }
    }

    private static String modelVersion(Map<?, ?> body) {
        String value = text(body, "model_version");
        return value != null ? value : text(body, "modelVersion");
    }

    /** A short string value of the body, or null; anything else (objects, long text) is ignored. */
    private static String text(Map<?, ?> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.length() > 100 ? text.substring(0, 100) : text;
        }
        return value instanceof Number number ? number.toString() : null;
    }
}
