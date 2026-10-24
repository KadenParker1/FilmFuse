package com.pm.filmfuse.common.job;

import com.pm.filmfuse.common.job.JobRunRepository.JobRun;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** app.job_runs (V20) against the real schema: round trip, retention, constraints, ml_reader. */
class JobRunHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobRunRepository runs;
    @Autowired
    private JobRunRecorder recorder;
    @Autowired
    private JdbcTemplate jdbc;

    /** Unique per test instance, so the shared database never mixes these rows with anything else. */
    private final String job = "test-" + UUID.randomUUID();

    @AfterEach
    void removeTestRuns() {
        jdbc.update("delete from app.job_runs where job_name = ?", job);
    }

    @Test
    void aRecordedRunRoundTripsWithItsJsonSummary() {
        String result = recorder.run(job, "admin:1", () -> "ok", value -> new JobRunRecorder.Finish(
                JobOutcome.COMPLETED, Map.of("mainTier", Map.of("fetched", 3), "durationSeconds", 12)));

        assertThat(result).isEqualTo("ok");
        List<JobRun> latest = runs.latest(job, 10);
        assertThat(latest).singleElement().satisfies(run -> {
            assertThat(run.outcome()).isEqualTo(JobOutcome.COMPLETED);
            assertThat(run.triggeredBy()).isEqualTo("admin:1");
            assertThat(run.finishedAt()).isAfterOrEqualTo(run.startedAt());
            assertThat(run.summary()).containsEntry("durationSeconds", 12);
            assertThat(run.summary().get("mainTier")).isEqualTo(Map.of("fetched", 3));
        });
        assertThat(jdbc.queryForObject("select (summary -> 'mainTier' ->> 'fetched')::int from app.job_runs "
                + "where job_name = ?", Integer.class, job)).isEqualTo(3);
    }

    @Test
    void aRunIsVisibleAsRunningWhileItRuns() {
        recorder.run(job, null, () -> {
            assertThat(runs.latest(job, 1)).singleElement().satisfies(run -> {
                assertThat(run.outcome()).isEqualTo(JobOutcome.RUNNING);
                assertThat(run.finishedAt()).isNull();
                assertThat(run.triggeredBy()).isNull();
            });
            return 1;
        }, value -> new JobRunRecorder.Finish(JobOutcome.COMPLETED, Map.of()));

        assertThat(runs.latest(job, 1).getFirst().outcome()).isEqualTo(JobOutcome.COMPLETED);
    }

    @Test
    void everyInsertPrunesTheJobToItsNewestRunsAndLeavesOtherJobsAlone() {
        String other = job + "-other";
        try {
            Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3_600);
            runs.insert(other, base, base, JobOutcome.COMPLETED, Map.of(), null);
            for (int i = 0; i < 5; i++) {
                runs.insert(job, base.plusSeconds(i), base.plusSeconds(i), JobOutcome.COMPLETED, Map.of("i", i), null);
            }
            JobRunRecorder keepThree = new JobRunRecorder(runs, Clock.systemUTC(), 3);

            keepThree.recordSkipped(job, "admin:2", "another catalog job is running");

            List<JobRun> kept = runs.latest(job, 10);
            assertThat(kept).hasSize(3);
            assertThat(kept.getFirst().outcome()).isEqualTo(JobOutcome.SKIPPED);
            assertThat(kept).extracting(run -> run.summary().get("i")).containsExactly(null, 4, 3);
            assertThat(runs.latest(other, 10)).hasSize(1);
        } finally {
            jdbc.update("delete from app.job_runs where job_name = ?", other);
        }
    }

    @Test
    void theDefaultRetentionIsTwoHundredRunsPerJob() {
        Instant base = Instant.now().minusSeconds(10_000);
        jdbc.update("""
                insert into app.job_runs (job_name, started_at, finished_at, outcome)
                select ?, ?::timestamptz + g * interval '1 second', ?::timestamptz + g * interval '1 second', 'COMPLETED'
                from generate_series(1, 205) g
                """, job, java.sql.Timestamp.from(base), java.sql.Timestamp.from(base));

        recorder.recordSkipped(job, null, "r");

        assertThat(jdbc.queryForObject("select count(*) from app.job_runs where job_name = ?", Integer.class, job))
                .isEqualTo(JobRunRecorder.DEFAULT_KEEP_PER_JOB);
    }

    @Test
    void outcomesAndNamesAreConstrained() {
        assertThatThrownBy(() -> jdbc.update("insert into app.job_runs (job_name, started_at, outcome) "
                + "values (?, now(), 'EXPLODED')", job)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app.job_runs (job_name, started_at, outcome) "
                + "values ('', now(), 'COMPLETED')")).isInstanceOf(DataIntegrityViolationException.class);
        for (JobOutcome outcome : JobOutcome.values()) {
            runs.insert(job, Instant.now(), null, outcome, Map.of(), null);
        }
        assertThat(runs.latest(job, 10)).hasSize(JobOutcome.values().length);
    }

    @Test
    void mlReaderCannotReadTheHistoryAndTheIndexExists() {
        assertThat(jdbc.queryForObject("select has_table_privilege('ml_reader', 'app.job_runs', 'SELECT')",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname = 'app' "
                + "and indexname = 'job_runs_job_started_idx'", Integer.class)).isEqualTo(1);
    }
}
