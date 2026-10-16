package com.pm.filmfuse.group.realtime;

import com.pm.filmfuse.common.http.RestClients;
import com.pm.filmfuse.user.gateway.SupabaseProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Real Supabase Realtime broadcasts with a service-role key; a logging mock without one. */
@Configuration
public class RealtimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConfig.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public RealtimeGateway realtimeGateway(SupabaseProperties properties) {
        if (!properties.hasServiceRoleKey()) {
            log.warn("SUPABASE_SERVICE_ROLE_KEY is not set: session updates use the mock realtime gateway "
                    + "(clients fall back to polling).");
            return new MockRealtimeGateway();
        }
        return new SupabaseRealtimeGateway(RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT), properties);
    }
}
