package com.pm.filmfuse.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.cors.allowed-origins}, a comma list fed by {@code APP_CORS_ALLOWED_ORIGINS}. */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
