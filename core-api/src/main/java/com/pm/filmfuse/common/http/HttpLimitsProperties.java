package com.pm.filmfuse.common.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** {@code filmfuse.http.max-body-size}: the request body cap (default 64KB), see {@link RequestBodyLimitFilter}. */
@ConfigurationProperties(prefix = "filmfuse.http")
public record HttpLimitsProperties(DataSize maxBodySize) {

    public static final DataSize DEFAULT_MAX_BODY_SIZE = DataSize.ofKilobytes(64);

    public HttpLimitsProperties {
        maxBodySize = maxBodySize == null ? DEFAULT_MAX_BODY_SIZE : maxBodySize;
        if (maxBodySize.toBytes() < 1) {
            throw new IllegalStateException("filmfuse.http.max-body-size must be positive");
        }
    }
}
