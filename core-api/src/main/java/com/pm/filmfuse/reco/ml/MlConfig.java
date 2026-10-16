package com.pm.filmfuse.reco.ml;

import com.pm.filmfuse.common.http.RestClients;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MlProperties.class)
public class MlConfig {

    private static final Logger log = LoggerFactory.getLogger(MlConfig.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public MlServiceClient mlServiceClient(MlProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("ML_SERVICE_URL is not set: group sessions use the in-process FallbackRanker (popularity order).");
        } else if (properties.internalKey().isBlank()) {
            log.warn("ML_INTERNAL_KEY is not set: the ML service will refuse core-api's requests.");
        }
        return new MlServiceClient(RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT), properties);
    }
}
