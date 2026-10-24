package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.admin.settings.DbCatalogSettings;
import com.pm.filmfuse.movie.catalog.CatalogJobLock;
import com.pm.filmfuse.movie.seed.CatalogRefreshService;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties;
import com.pm.filmfuse.movie.seed.CatalogSeedService;
import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Jobs: status from app.job_runs, Run now (202 with the run id / 409 while a catalog job holds the
 * lock), Pause / Resume through the settings the schedulers read, and the DB-backed catalog settings.
 */
class AdminJobsIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private CatalogRefreshSettings refreshSettings;
    @Autowired
    private DbCatalogSettings dbSettings;
    @Autowired
    private CatalogSeedProperties seedProperties;
    @Autowired
    private CatalogSeedService seedService;
    @Autowired
    private CatalogRefreshService refreshService;

    private UUID admin;

    @BeforeEach
    void clean() {
        jdbc.update("delete from app.job_runs");
        jdbc.update("delete from app.admin_settings");
        admin = newAdmin();
    }

    private void run(String job, Instant started, Instant finished, String outcome, String triggeredBy) {
        jdbc.update("""
                insert into app.job_runs (job_name, started_at, finished_at, outcome, summary, triggered_by)
                values (?, ?, ?, ?, '{"fetched": 3}'::jsonb, ?)
                """, job, Timestamp.from(started), finished == null ? null : Timestamp.from(finished), outcome, triggeredBy);
    }

    private void awaitNoRunningRuns() throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            Long running = jdbc.queryForObject("select count(*) from app.job_runs where finished_at is null", Long.class);
            if (running != null && running == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("a job run never finished");
    }

    @Test
    void listsEveryJobWithItsLastAndRecentRuns() throws Exception {
        Instant base = Instant.parse("2026-10-01T04:00:00Z");
        for (int i = 0; i < 7; i++) {
            run("catalog-top-up", base.plus(Duration.ofDays(i)), base.plus(Duration.ofDays(i)).plusSeconds(60),
                    "COMPLETED", null);
        }
        run("closeness", base, base.plusSeconds(5), "FAILED", null);

        mockMvc.perform(get("/api/v1/admin/jobs").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("catalog-top-up", "catalog-refresh",
                        "catalog-enrichment", "vector-refresh", "closeness", "weekly-digest", "catalog-seed")))
                .andExpect(jsonPath("$[0].label").value("Nightly catalog top-up"))
                .andExpect(jsonPath("$[0].controllable").value(true))
                // Off under the test profile: not scheduled here, so no next run.
                .andExpect(jsonPath("$[0].scheduled").value(false))
                .andExpect(jsonPath("$[0].nextRunAt").doesNotExist())
                .andExpect(jsonPath("$[0].schedule").value("0 0 4 * * *"))
                .andExpect(jsonPath("$[0].paused").value(false))
                .andExpect(jsonPath("$[0].running").value(false))
                .andExpect(jsonPath("$[0].lastRun.startedAt").value(base.plus(Duration.ofDays(6)).toString()))
                .andExpect(jsonPath("$[0].lastRun.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$[0].lastRun.summary.fetched").value(3))
                .andExpect(jsonPath("$[0].recentRuns.length()").value(5))
                .andExpect(jsonPath("$[1].lastRun").doesNotExist())
                .andExpect(jsonPath("$[1].schedule").value("0 0 5 * * SUN"))
                .andExpect(jsonPath("$[2].controllable").value(false))
                .andExpect(jsonPath("$[2].schedule").value("every PT10M"))
                .andExpect(jsonPath("$[4].lastRun.outcome").value("FAILED"))
                .andExpect(jsonPath("$[6].schedule").doesNotExist());
    }

    @Test
    void nextRunComesFromTheCron() {
        Instant now = Instant.parse("2026-10-23T10:00:00Z"); // a Friday
        assertThat(com.pm.filmfuse.admin.service.AdminJobService.nextRun("0 0 4 * * *", now))
                .isEqualTo(Instant.parse("2026-10-24T04:00:00Z"));
        assertThat(com.pm.filmfuse.admin.service.AdminJobService.nextRun("0 0 5 * * SUN", now))
                .isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
        assertThat(com.pm.filmfuse.admin.service.AdminJobService.nextRun("not a cron", now)).isNull();
    }

    @Test
    void runNowStartsTheJobInTheBackgroundAndReturns202WithTheRunId() throws Exception {
        String json = mockMvc.perform(post("/api/v1/admin/jobs/catalog-top-up/run").with(as(admin)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("catalog-top-up"))
                .andExpect(jsonPath("$.triggeredBy").value("admin:" + admin))
                .andExpect(jsonPath("$.runId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long runId = ((Number) JsonPath.read(json, "$.runId")).longValue();
        awaitNoRunningRuns();

        assertThat(jdbc.queryForMap("select job_name, triggered_by, outcome from app.job_runs where id = ?", runId))
                .containsEntry("job_name", "catalog-top-up")
                .containsEntry("triggered_by", "admin:" + admin)
                .containsEntry("outcome", "COMPLETED");
        assertThat(jdbc.queryForObject("""
                select new_value->>'runId' from app.admin_audit_log where admin_id = ? and action = 'JOB_RUN'
                """, String.class, admin)).isEqualTo(Long.toString(runId));
        mockMvc.perform(get("/api/v1/admin/jobs").with(as(admin)))
                .andExpect(jsonPath("$[0].lastRun.id").value(runId))
                .andExpect(jsonPath("$[0].lastRun.triggeredBy").value("admin:" + admin));
    }

    @Test
    void runNowIs409WhileACatalogJobHoldsTheLock() throws Exception {
        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            statement.execute("select pg_advisory_lock(" + CatalogJobLock.KEY + ")");
            try {
                mockMvc.perform(post("/api/v1/admin/jobs/catalog-refresh/run").with(as(admin)))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("ALREADY_RUNNING")));
            } finally {
                statement.execute("select pg_advisory_unlock(" + CatalogJobLock.KEY + ")");
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from app.admin_audit_log where admin_id = ?", Long.class, admin))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from app.job_runs", Long.class)).isZero();
    }

    @Test
    void onlyCatalogJobsHaveControlsAndUnknownJobsAre404() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/closeness/run").with(as(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/jobs/weekly-digest/pause").with(as(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/jobs/nope/run").with(as(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Job not found."));
        mockMvc.perform(post("/api/v1/admin/jobs/nope/resume").with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void pauseAndResumeFlipTheFlagTheSchedulerReadsAndAreAudited() throws Exception {
        mockMvc.perform(post("/api/v1/admin/jobs/catalog-refresh/pause").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("catalog-refresh"))
                .andExpect(jsonPath("$.paused").value(true));
        assertThat(dbSettings.isPaused("catalog-refresh")).isTrue();
        assertThat(dbSettings.isPaused("catalog-top-up")).isFalse();
        assertThat(refreshSettings.current().refreshEnabled()).isFalse();

        mockMvc.perform(post("/api/v1/admin/jobs/catalog-refresh/resume").with(as(admin)))
                .andExpect(jsonPath("$.paused").value(false));
        assertThat(dbSettings.isPaused("catalog-refresh")).isFalse();

        assertThat(jdbc.queryForList("""
                select action from app.admin_audit_log where admin_id = ? and target = 'catalog-refresh' order by created_at
                """, String.class, admin)).containsExactly("JOB_PAUSE", "JOB_RESUME");
        // The pause flag is a registered setting too.
        mockMvc.perform(get("/api/v1/admin/settings").with(as(admin)))
                .andExpect(jsonPath("$[?(@.key == 'jobs.catalog-refresh.paused')].value").value(false));
    }

    @Test
    void theDbBackedSettingsAreTheOnesTheJobsUseAndFollowTheAdminSettings() throws Exception {
        assertThat(refreshSettings).isSameAs(dbSettings);
        assertThat(ReflectionTestUtils.getField(seedService, "overrides")).isSameAs(dbSettings);
        assertThat(ReflectionTestUtils.getField(refreshService, "overrides")).isSameAs(dbSettings);

        // No overrides: exactly the configured values.
        CatalogRefreshSettings.Settings configured = refreshSettings.current();
        assertThat(configured.topUpPages()).isEqualTo(50);
        assertThat(configured.mainTierSize()).isEqualTo(20_000);
        assertThat(dbSettings.apply(seedProperties)).isSameAs(seedProperties);

        put("catalog.top-up.size", 250);
        put("catalog.refresh.main-tier-size", 5000);
        put("catalog.requests-per-second", 35);
        put("catalog.max-database-mb", 300);
        put("catalog.quality.min-votes", 40);
        put("catalog.quality.require-poster", false);
        put("catalog.quality.require-overview", false);

        CatalogRefreshSettings.Settings current = refreshSettings.current();
        assertThat(current.topUpPages()).isEqualTo(13);
        assertThat(current.mainTierSize()).isEqualTo(5000);
        assertThat(current.refreshCron()).isEqualTo(configured.refreshCron());
        CatalogSeedProperties effective = dbSettings.apply(seedProperties);
        assertThat(effective.requestsPerSecond()).isEqualTo(35d);
        assertThat(effective.maxDatabaseMb()).isEqualTo(300);
        assertThat(effective.quality().minVotes()).isEqualTo(40);
        assertThat(effective.quality().requirePoster()).isFalse();
        assertThat(effective.quality().requireOverview()).isFalse();
        // Untouched values stay as configured.
        assertThat(effective.quality().requireRuntime()).isEqualTo(seedProperties.quality().requireRuntime());
        assertThat(effective.sources()).isEqualTo(seedProperties.sources());
        assertThat(effective.maxRetries()).isEqualTo(seedProperties.maxRetries());
    }

    private void put(String key, Object value) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/admin/settings/{key}", key).with(as(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + value + "}"))
                .andExpect(status().isOk());
    }
}
