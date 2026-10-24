package com.pm.filmfuse.common.config;

import com.pm.filmfuse.admin.security.AdminAccess;
import com.pm.filmfuse.common.http.HttpLimitsProperties;
import com.pm.filmfuse.common.http.RequestBodyLimitFilter;
import com.pm.filmfuse.common.ratelimit.RateLimitFilter;
import com.pm.filmfuse.common.ratelimit.RateLimiter;
import com.pm.filmfuse.user.repository.DeletedAccountRepository;
import com.pm.filmfuse.user.repository.ProfileRepository;
import com.pm.filmfuse.user.security.SupabaseJwtAuthenticationConverter;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless OAuth2 resource server. Supabase issues the JWTs (ES256); we verify them against the
 * project's JWKS ({@code spring.security.oauth2.resourceserver.jwt.*}). No sessions, no CSRF -
 * the client sends a bearer token by hand, never a cookie.
 *
 * <p>Two filters of our own run in the chain, both after CORS (so a browser can read their errors):
 * {@link RequestBodyLimitFilter} before authentication (413 for an oversized body) and
 * {@link RateLimitFilter} right after it (429 per user / IP / category). Spring Security's default
 * headers stay on: {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate},
 * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}, HSTS over HTTPS.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({CorsProperties.class, HttpLimitsProperties.class})
public class SecurityConfig {

    /** Public off-prod only; springdoc is switched off entirely under {@code prod}. */
    private static final String[] API_DOC_PATHS = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SupabaseJwtAuthenticationConverter jwtAuthenticationConverter,
                                           CorsConfigurationSource corsConfigurationSource,
                                           RateLimiter rateLimiter,
                                           HttpLimitsProperties httpLimits,
                                           Environment environment) throws Exception {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> {
                    // Emitted only over HTTPS; server.forward-headers-strategy=framework (prod) makes
                    // Railway's TLS-terminating proxy count as HTTPS.
                    headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(63072000));
                    headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    if (prod) {
                        // Nothing this API returns is meant to render as a document. Off-prod Swagger UI needs scripts.
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"));
                    }
                })
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (!prod) {
                        auth.requestMatchers(API_DOC_PATHS).permitAll();
                    }
                    // Admin portal: ROLE_ADMIN only; signed-in non-admins get a 404 (AdminAccess).
                    auth.requestMatchers(AdminAccess.PATH_PATTERN).hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(AdminAccess.accessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .accessDeniedHandler(AdminAccess.accessDeniedHandler()))
                .addFilterBefore(new RequestBodyLimitFilter(httpLimits.maxBodySize().toBytes()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public SupabaseJwtAuthenticationConverter supabaseJwtAuthenticationConverter(
            ProfileRepository profileRepository, DeletedAccountRepository deletedAccountRepository) {
        return new SupabaseJwtAuthenticationConverter(profileRepository, deletedAccountRepository);
    }

    /** Bearer-token client, so credentialed CORS is deliberately off. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins() == null ? List.of() : corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
