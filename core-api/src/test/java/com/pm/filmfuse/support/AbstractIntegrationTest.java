package com.pm.filmfuse.support;

import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full Spring context against a real Testcontainers Postgres, with the real Flyway migrations.
 *
 * <p>The container starts once in a static initializer and is never stopped here: it is shared
 * (through the Spring context cache) by every subclass in the JVM. The JUnit
 * {@code @Container} extension would stop it after the first class. Ryuk reaps it at JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("filmfuse")
            .withUsername("filmfuse")
            .withPassword("filmfuse");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=app");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A fresh signed-in caller nobody else in the suite shares. */
    protected RequestPostProcessor someUser() {
        return JwtTestSupport.supabaseUser(UUID.randomUUID());
    }
}
