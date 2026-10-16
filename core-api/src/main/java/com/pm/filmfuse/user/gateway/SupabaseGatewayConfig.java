package com.pm.filmfuse.user.gateway;

import com.pm.filmfuse.common.http.RestClients;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Real Supabase Storage / Auth-admin gateways when {@code filmfuse.supabase.service-role-key} is
 * set; logging mocks when it is blank (local dev without keys, tests).
 */
@Configuration
@EnableConfigurationProperties(SupabaseProperties.class)
public class SupabaseGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SupabaseGatewayConfig.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public StorageGateway storageGateway(SupabaseProperties properties) {
        if (!properties.hasServiceRoleKey()) {
            log.warn("SUPABASE_SERVICE_ROLE_KEY is not set: profile photos use the mock storage gateway "
                    + "(nothing is uploaded or deleted).");
            return new MockStorageGateway(properties);
        }
        return new SupabaseStorageGateway(RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT), properties);
    }

    @Bean
    public AuthAdminGateway authAdminGateway(SupabaseProperties properties) {
        if (!properties.hasServiceRoleKey()) {
            log.warn("SUPABASE_SERVICE_ROLE_KEY is not set: account deletion uses the mock auth-admin gateway "
                    + "(Supabase Auth users are not deleted).");
            return new MockAuthAdminGateway();
        }
        return new SupabaseAuthAdminGateway(RestClients.withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT), properties);
    }
}
