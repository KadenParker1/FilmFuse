package com.pm.filmfuse.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigValidatorTest {

    private static MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/postgres")
                .withProperty("spring.datasource.username", "core")
                .withProperty("spring.datasource.password", "secret")
                .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "https://abc.supabase.co/auth/v1/.well-known/jwks.json")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://abc.supabase.co/auth/v1")
                .withProperty("filmfuse.supabase.url", "https://abc.supabase.co")
                .withProperty("filmfuse.supabase.service-role-key", "service-role")
                .withProperty("filmfuse.app.web-url", "https://filmfuse.vercel.app")
                .withProperty("app.cors.allowed-origins", "https://filmfuse.vercel.app")
                .withProperty("filmfuse.ml.base-url", "http://python-ml-service.railway.internal:8001")
                .withProperty("filmfuse.ml.internal-key", "0123456789abcdef0123456789abcdef")
                .withProperty("filmfuse.email.provider", "smtp")
                .withProperty("spring.mail.password", "resend-key")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false");
    }

    @Test
    void aCompleteConfigurationPasses() {
        assertThat(ProductionConfigValidator.problems(valid())).isEmpty();
        new ProductionConfigValidator(valid());
    }

    @Test
    void aBlankServiceRoleKeyFailsInsteadOfSelectingTheMockGateways() {
        MockEnvironment env = valid().withProperty("filmfuse.supabase.service-role-key", " ");
        assertThat(ProductionConfigValidator.problems(env)).containsExactly("filmfuse.supabase.service-role-key must be set");
        assertThatThrownBy(() -> new ProductionConfigValidator(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filmfuse.supabase.service-role-key")
                .hasMessageNotContaining("secret");
    }

    @Test
    void anUnresolvedPlaceholderCountsAsMissing() {
        MockEnvironment env = valid().withProperty("spring.datasource.password", "${DATABASE_PASSWORD}");
        assertThat(ProductionConfigValidator.problems(env)).containsExactly("spring.datasource.password must be set");
    }

    @Test
    void localOrPlainHttpUrlsAreRefused() {
        MockEnvironment env = valid()
                .withProperty("filmfuse.supabase.url", "http://127.0.0.1:55321")
                .withProperty("filmfuse.app.web-url", "http://filmfuse.vercel.app");
        assertThat(ProductionConfigValidator.problems(env)).containsExactly(
                "filmfuse.supabase.url must be an https URL of a non-local host",
                "filmfuse.app.web-url must be an https URL of a non-local host");
    }

    @Test
    void corsMustListExactHttpsOrigins() {
        assertThat(ProductionConfigValidator.problems(valid().withProperty("app.cors.allowed-origins", "*")))
                .anyMatch(p -> p.startsWith("app.cors.allowed-origins"));
        assertThat(ProductionConfigValidator.problems(valid().withProperty("app.cors.allowed-origins",
                "https://filmfuse.vercel.app, http://localhost:8081"))).anyMatch(p -> p.startsWith("app.cors"));
        assertThat(ProductionConfigValidator.problems(valid().withProperty("app.cors.allowed-origins", "")))
                .containsExactly("app.cors.allowed-origins must list the web app's origin");
        assertThat(ProductionConfigValidator.problems(valid().withProperty("app.cors.allowed-origins",
                "https://filmfuse.vercel.app,https://www.filmfuse.app"))).isEmpty();
    }

    @Test
    void anMlServiceNeedsItsKeyAndSmtpNeedsAPassword() {
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.ml.internal-key", "")))
                .containsExactly("filmfuse.ml.internal-key must be set when filmfuse.ml.base-url is");
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.ml.base-url", "")
                .withProperty("filmfuse.ml.internal-key", ""))).isEmpty();
        assertThat(ProductionConfigValidator.problems(valid().withProperty("spring.mail.password", "")))
                .containsExactly("spring.mail.password must be set when filmfuse.email.provider=smtp");
    }

    @Test
    void theMlKeyMustBeAtLeastThirtyTwoCharacters() {
        String shortKey = "k".repeat(31);
        List<String> problems = ProductionConfigValidator.problems(
                valid().withProperty("filmfuse.ml.internal-key", shortKey));
        assertThat(problems).containsExactly("filmfuse.ml.internal-key must be at least 32 characters");
        assertThat(String.join(" ", problems)).doesNotContain(shortKey);
        // Surrounding whitespace does not count towards the length.
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.ml.internal-key",
                "  " + shortKey + "   "))).containsExactly("filmfuse.ml.internal-key must be at least 32 characters");
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.ml.internal-key", "k".repeat(32))))
                .isEmpty();
        // Without an ML URL the key is unused, so its length does not matter.
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.ml.base-url", "")
                .withProperty("filmfuse.ml.internal-key", "short"))).isEmpty();
    }

    @Test
    void rateLimitingAndSwaggerCannotBeFlippedInProd() {
        assertThat(ProductionConfigValidator.problems(valid().withProperty("filmfuse.rate-limit.enabled", "false")))
                .containsExactly("filmfuse.rate-limit.enabled must not be false");
        assertThat(ProductionConfigValidator.problems(valid().withProperty("springdoc.swagger-ui.enabled", "true")))
                .containsExactly("springdoc must stay disabled");
    }

    /** The prod profile file itself: docs off, details hidden, audience checked, rate limiting on. */
    @Test
    void theProdProfileFileKeepsTheHardenedSettings() throws Exception {
        Properties prod = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-prod.properties")) {
            prod.load(in);
        }
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(prod.getProperty("spring.security.oauth2.resourceserver.jwt.audiences")).isEqualTo("authenticated");
        assertThat(prod.getProperty("spring.security.oauth2.resourceserver.jwt.jws-algorithms")).isEqualTo("ES256");
        assertThat(prod.getProperty("filmfuse.rate-limit.enabled")).isEqualTo("true");
        assertThat(prod.getProperty("filmfuse.supabase.service-role-key")).isEqualTo("${SUPABASE_SERVICE_ROLE_KEY}");
    }
}
