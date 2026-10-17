package com.pm.filmfuse.reco.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Properties always; the scheduled tick only when {@code filmfuse.vectors.refresh.enabled} (default true). */
@Configuration
@EnableConfigurationProperties(VectorRefreshProperties.class)
public class VectorRefreshConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "filmfuse.vectors.refresh.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
