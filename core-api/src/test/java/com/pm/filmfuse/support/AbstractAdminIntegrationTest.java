package com.pm.filmfuse.support;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Admin-portal tests: a database of their own ({@code filmfuse_admin}, created once in the shared
 * Testcontainers Postgres and migrated by Flyway), so settings, audit rows and metrics fixtures never
 * leak into the shared test database and metrics can be asserted exactly.
 *
 * <p>Requests authenticate through the real chain - a bearer token decoded by a mocked
 * {@link JwtDecoder} (the token is the user id) and then {@code SupabaseJwtAuthenticationConverter},
 * which reads {@code profiles.role}. So "admin" here means exactly what it means in production: the
 * row says so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractAdminIntegrationTest {

    private static final String DATABASE = "filmfuse_admin";

    static {
        // Starts the shared container (AbstractIntegrationTest's static initializer) if needed.
        PostgresHolder.ensureDatabase(DATABASE);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresHolder.jdbcUrl(DATABASE) + "?currentSchema=app");
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
    }

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void decodeTokensAsUserIds() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "ES256")
                    .subject(token)
                    .claim("email", JwtTestSupport.emailFor(UUID.fromString(token)))
                    .claim("role", "authenticated")
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .build();
        });
    }

    /** A bearer token for {@code userId}, authenticated through the real converter. */
    protected static RequestPostProcessor as(UUID userId) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + userId);
            return request;
        };
    }

    /** A provisioned profile with role {@code user}. */
    protected UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app.profiles (id, username, display_name, email) values (?, ?, ?, ?)", id,
                "u" + id.toString().replace("-", "").substring(0, 12), "User " + id.toString().substring(0, 4),
                JwtTestSupport.emailFor(id));
        return id;
    }

    /** A provisioned profile granted admin the only way there is: SQL. */
    protected UUID newAdmin() {
        UUID id = newUser();
        jdbc.update("update app.profiles set role = 'admin' where id = ?", id);
        return id;
    }

    /** Creates the database once per JVM inside the shared container. */
    static final class PostgresHolder {

        private static volatile boolean created;

        private PostgresHolder() {
        }

        static String jdbcUrl(String database) {
            return "jdbc:postgresql://" + AbstractIntegrationTest.POSTGRES.getHost() + ":"
                    + AbstractIntegrationTest.POSTGRES.getMappedPort(5432) + "/" + database;
        }

        static synchronized void ensureDatabase(String database) {
            if (created) {
                return;
            }
            try (Connection connection = DriverManager.getConnection(AbstractIntegrationTest.POSTGRES.getJdbcUrl(),
                    AbstractIntegrationTest.POSTGRES.getUsername(), AbstractIntegrationTest.POSTGRES.getPassword());
                 Statement statement = connection.createStatement()) {
                var exists = statement.executeQuery("select 1 from pg_database where datname = '" + database + "'");
                if (!exists.next()) {
                    statement.execute("create database " + database);
                }
                created = true;
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not create the admin test database", ex);
            }
        }
    }
}
