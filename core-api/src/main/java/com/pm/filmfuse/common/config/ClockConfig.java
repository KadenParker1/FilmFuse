package com.pm.filmfuse.common.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The application clock, a bean so tests can move time (e.g. invite expiry) without sleeping. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
