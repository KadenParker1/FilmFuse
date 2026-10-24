package com.pm.filmfuse.admin;

import com.pm.filmfuse.common.http.RestClients;
import com.pm.filmfuse.reco.ml.MlHealthProbe;
import com.pm.filmfuse.reco.ml.MlProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {

    /** The admin health panel's ML check: 1 s to connect, {@code filmfuse.admin.ml-health-timeout} to answer. */
    @Bean
    public MlHealthProbe mlHealthProbe(MlProperties mlProperties, AdminProperties adminProperties, Clock clock) {
        return new MlHealthProbe(RestClients.withTimeouts(Duration.ofSeconds(1), adminProperties.mlHealthTimeout()),
                mlProperties, clock);
    }
}
