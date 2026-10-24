package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The metrics SQL against exact fixtures. This class empties the admin test database's tables first
 * (it is not the shared test database), so every count is known.
 */
class AdminMetricsIntegrationTest extends AbstractAdminIntegrationTest {

    private UUID admin;
    private Instant now;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("""
                truncate app.profiles, app.movies, app.catalog_seed_rejects, app.reports, app.admin_settings,
                         app.admin_audit_log cascade
                """);
        now = Instant.now();
        admin = newAdmin();
    }

    private UUID userCreated(Duration ago, boolean onboarded) {
        UUID id = newUser();
        Instant created = now.minus(ago);
        jdbc.update("update app.profiles set created_at = ?, onboarding_completed_at = ? where id = ?",
                Timestamp.from(created), onboarded ? Timestamp.from(created) : null, id);
        return id;
    }

    private void movie(int id, Integer voteCount, String certification) {
        jdbc.update("insert into app.movies (id, title, cached_at, vote_count, certification) values (?, ?, now(), ?, ?)",
                id, "Movie " + id, voteCount, certification);
    }

    private void rating(UUID user, int movieId, Instant at) {
        jdbc.update("""
                insert into app.ratings (id, user_id, movie_id, score, source, created_at, updated_at)
                values (?, ?, ?, 7, 'quick_rate', ?, ?)
                """, UUID.randomUUID(), user, movieId, Timestamp.from(at), Timestamp.from(at));
    }

    private UUID group(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app.groups (id, name, owner_id) values (?, 'Crew', ?)", id, owner);
        return id;
    }

    private void session(UUID group, Duration createdAgo, Integer pickedMovie, Duration decidedAgo) {
        jdbc.update("""
                insert into app.rec_sessions (id, group_id, status, picked_movie_id, decided_at, created_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), group, pickedMovie == null ? "OPEN" : "DECIDED", pickedMovie,
                decidedAgo == null ? null : Timestamp.from(now.minus(decidedAgo)),
                Timestamp.from(now.minus(createdAgo)));
    }

    @Test
    void catalogCountsTiersRejectsAndTheSizeGuard() throws Exception {
        movie(1, 1500, "PG-13");
        movie(2, 1000, "R");
        movie(3, 999, null);
        movie(4, 100, "G");
        movie(5, 99, null);
        movie(6, 20, null);
        movie(7, 19, null);
        movie(8, 0, null);
        movie(9, null, null);
        movie(10, null, null);
        jdbc.update("insert into app.catalog_seed_rejects (movie_id) values (501), (502)");

        String json = mockMvc.perform(get("/api/v1/admin/metrics/catalog").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMovies").value(10))
                .andExpect(jsonPath("$.withCertification").value(3))
                .andExpect(jsonPath("$.voteTiers.votes1000Plus").value(2))
                .andExpect(jsonPath("$.voteTiers.votes100To999").value(2))
                .andExpect(jsonPath("$.voteTiers.votes20To99").value(2))
                .andExpect(jsonPath("$.voteTiers.votesUnder20").value(2))
                .andExpect(jsonPath("$.voteTiers.votesUnknown").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.maxDatabaseMb").value(450))
                .andExpect(jsonPath("$.maxDatabaseMbCeiling").value(480))
                .andExpect(jsonPath("$.planLimitMb").value(500))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long bytes = ((Number) JsonPath.read(json, "$.databaseBytes")).longValue();
        assertThat(bytes).isCloseTo(jdbc.queryForObject("select pg_database_size(current_database())", Long.class),
                org.assertj.core.data.Offset.offset(2_000_000L));

        // The guard follows the runtime setting.
        mockMvc.perform(put("/api/v1/admin/settings/catalog.max-database-mb").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": 300}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/metrics/catalog").with(as(admin)))
                .andExpect(jsonPath("$.maxDatabaseMb").value(300));
    }

    @Test
    void anEmptyCatalogIsAllZeros() throws Exception {
        mockMvc.perform(get("/api/v1/admin/metrics/catalog").with(as(admin)))
                .andExpect(jsonPath("$.totalMovies").value(0))
                .andExpect(jsonPath("$.voteTiers.votesUnknown").value(0))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    @Test
    void usageCountsUsersRatingsPerDaySessionsAndActiveGroups() throws Exception {
        // The admin (created now, not onboarded) + 3 more.
        UUID fresh = userCreated(Duration.ofDays(1), true);
        UUID older = userCreated(Duration.ofDays(10), true);
        userCreated(Duration.ofDays(3), false);

        movie(1, 10, null);
        movie(2, 10, null);
        movie(3, 10, null);
        movie(4, 10, null);
        movie(5, 10, null);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant todayNoon = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        rating(fresh, 1, todayStart);
        rating(older, 1, todayStart.plusSeconds(1));
        rating(fresh, 2, todayNoon.minus(Duration.ofDays(1)));
        rating(fresh, 3, todayNoon.minus(Duration.ofDays(29)));
        rating(fresh, 4, todayNoon.minus(Duration.ofDays(30)));   // outside the 30-day window
        rating(older, 5, todayStart.minusSeconds(1));              // the last second of yesterday

        UUID g1 = group(older);
        UUID g2 = group(older);
        UUID g3 = group(older);
        session(g1, Duration.ofDays(2), 1, Duration.ofDays(1));    // started 7d, picked 7d
        session(g1, Duration.ofDays(10), null, null);              // started 30d
        session(g2, Duration.ofDays(20), 2, Duration.ofDays(15));  // started 30d, picked 30d
        session(g3, Duration.ofDays(40), 3, Duration.ofDays(6));   // started long ago, picked within 7d
        session(null, Duration.ofDays(1), null, null);             // ad-hoc, started 7d

        String json = mockMvc.perform(get("/api/v1/admin/metrics/usage").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usersTotal").value(4))
                .andExpect(jsonPath("$.usersNew7d").value(3))
                .andExpect(jsonPath("$.usersOnboarded").value(2))
                .andExpect(jsonPath("$.ratingsPerDay.length()").value(30))
                .andExpect(jsonPath("$.sessionsStarted7d").value(2))
                .andExpect(jsonPath("$.sessionsStarted30d").value(4))
                .andExpect(jsonPath("$.sessionsPicked7d").value(2))
                .andExpect(jsonPath("$.sessionsPicked30d").value(3))
                .andExpect(jsonPath("$.activeGroups30d").value(2))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> days = JsonPath.read(json, "$.ratingsPerDay");
        assertThat(days.getFirst().get("date")).isEqualTo(today.minusDays(29).toString());
        assertThat(days.getLast().get("date")).isEqualTo(today.toString());
        assertThat(((Number) days.getLast().get("count")).longValue()).isEqualTo(2);
        assertThat(((Number) days.get(28).get("count")).longValue()).isEqualTo(2);
        assertThat(((Number) days.getFirst().get("count")).longValue()).isEqualTo(1);
        long total = days.stream().mapToLong(day -> ((Number) day.get("count")).longValue()).sum();
        assertThat(total).isEqualTo(5);
    }

    @Test
    void healthCountsDirtyVectorsAndOpenReportsAndSaysTheMlServiceIsNotConfigured() throws Exception {
        UUID a = newUser();
        UUID b = newUser();
        UUID c = newUser();
        Instant oldest = now.minus(Duration.ofMinutes(30)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("""
                insert into app.user_interest_vectors (user_id, dirty, dirty_reason, dirty_since)
                values (?, true, 'RATING', ?), (?, true, 'ONBOARDING', ?)
                """, a, Timestamp.from(oldest), b, Timestamp.from(now));
        jdbc.update("""
                insert into app.user_interest_vectors (user_id, interpretable, version, model_version)
                values (?, '{0.5}', 3, 'fallback-0')
                """, c);
        jdbc.update("""
                insert into app.reports (id, reporter_id, target_type, target_id, reason) values
                (?, ?, 'USER', ?, 'SPAM'), (?, ?, 'USER', ?, 'SPAM')
                """, UUID.randomUUID(), a, b, UUID.randomUUID(), b, a);
        jdbc.update("""
                insert into app.reports (id, reporter_id, target_type, target_id, reason, status, resolved_at)
                values (?, ?, 'USER', ?, 'SPAM', 'DISMISSED', now())
                """, UUID.randomUUID(), c, a);

        mockMvc.perform(get("/api/v1/admin/metrics/health").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ml.configured").value(false))
                .andExpect(jsonPath("$.ml.reachable").value(false))
                .andExpect(jsonPath("$.ml.latencyMs").doesNotExist())
                .andExpect(jsonPath("$.vectorsDirty").value(2))
                .andExpect(jsonPath("$.vectorsDirtyOldest").value(oldest.toString()))
                .andExpect(jsonPath("$.vectorsNeverBuilt").value(2))
                .andExpect(jsonPath("$.openReports").value(2));
    }
}
