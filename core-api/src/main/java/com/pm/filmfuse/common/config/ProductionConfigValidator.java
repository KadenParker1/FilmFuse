package com.pm.filmfuse.common.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under {@code prod} when a required secret or setting is missing or unsafe. Each
 * of these otherwise degrades silently: a blank service-role key selects the mock storage /
 * auth-admin gateways (account deletion would leave the Supabase user behind), a blank
 * {@code SUPABASE_URL} points at localhost, a {@code *} CORS origin admits every site.
 *
 * <p>Problems name property keys only, never values.
 */
@Component
@Profile("prod")
public class ProductionConfigValidator {

    /** The ML service refuses a shorter key outside ENV=local too (SECURITY.md #20). */
    static final int MIN_ML_INTERNAL_KEY_LENGTH = 32;

    public ProductionConfigValidator(Environment environment) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start under the prod profile: " + String.join("; ", problems));
        }
    }

    /** Every problem found; empty when the configuration is fit for production. */
    public static List<String> problems(PropertyResolver env) {
        List<String> problems = new ArrayList<>();
        for (String key : new String[] {"spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password", "filmfuse.supabase.service-role-key"}) {
            if (isBlank(get(env, key))) {
                problems.add(key + " must be set");
            }
        }
        for (String key : new String[] {"spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "filmfuse.supabase.url", "filmfuse.app.web-url"}) {
            String value = get(env, key);
            if (isBlank(value)) {
                problems.add(key + " must be set");
            } else if (!isRemoteHttps(value)) {
                problems.add(key + " must be an https URL of a non-local host");
            }
        }

        String origins = get(env, "app.cors.allowed-origins");
        List<String> originList = isBlank(origins) ? List.of()
                : Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (originList.isEmpty()) {
            problems.add("app.cors.allowed-origins must list the web app's origin");
        }
        for (String origin : originList) {
            if (origin.contains("*") || !isRemoteHttps(origin)) {
                problems.add("app.cors.allowed-origins entries must be exact https origins (no wildcards, no localhost)");
                break;
            }
        }

        if (!isBlank(get(env, "filmfuse.ml.base-url"))) {
            String key = trim(get(env, "filmfuse.ml.internal-key"));
            if (isBlank(key)) {
                problems.add("filmfuse.ml.internal-key must be set when filmfuse.ml.base-url is");
            } else if (key.length() < MIN_ML_INTERNAL_KEY_LENGTH) {
                problems.add("filmfuse.ml.internal-key must be at least " + MIN_ML_INTERNAL_KEY_LENGTH + " characters");
            }
        }
        if ("smtp".equalsIgnoreCase(trim(get(env, "filmfuse.email.provider")))
                && isBlank(get(env, "spring.mail.password"))) {
            problems.add("spring.mail.password must be set when filmfuse.email.provider=smtp");
        }
        if ("false".equalsIgnoreCase(trim(get(env, "filmfuse.rate-limit.enabled")))) {
            problems.add("filmfuse.rate-limit.enabled must not be false");
        }
        if ("true".equalsIgnoreCase(trim(get(env, "springdoc.api-docs.enabled")))
                || "true".equalsIgnoreCase(trim(get(env, "springdoc.swagger-ui.enabled")))) {
            problems.add("springdoc must stay disabled");
        }
        return problems;
    }

    /** An unresolvable placeholder (env var not set) counts as missing. */
    private static String get(PropertyResolver env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isRemoteHttps(String value) {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return !host.equals("localhost") && !host.startsWith("127.") && !host.equals("[::1]") && !host.equals("0.0.0.0");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
