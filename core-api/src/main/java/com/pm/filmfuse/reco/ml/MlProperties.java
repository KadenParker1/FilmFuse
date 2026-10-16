package com.pm.filmfuse.reco.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl     python-ml-service root ({@code ML_SERVICE_URL}, Railway private networking);
 *                    blank = never call it, always use the in-process fallback ranker
 * @param internalKey shared secret sent as {@code X-Internal-Key} ({@code ML_INTERNAL_KEY})
 */
@ConfigurationProperties(prefix = "filmfuse.ml")
public record MlProperties(String baseUrl, String internalKey) {

    public MlProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        internalKey = internalKey == null ? "" : internalKey.trim();
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }
}
