package com.pm.filmfuse.common.job;

import com.pm.filmfuse.common.json.JsonColumns;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app.job_runs} (V20). Each call autocommits, so a run's row survives the run failing. */
@Repository
public class JobRunRepository {

    /** One stored run. {@code finishedAt} null while running (or when the process died). */
    public record JobRun(long id, String jobName, Instant startedAt, Instant finishedAt, JobOutcome outcome,
                         Map<String, Object> summary, String triggeredBy) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public JobRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a run and returns its id. */
    public long insert(String jobName, Instant startedAt, Instant finishedAt, JobOutcome outcome,
                       Map<String, Object> summary, String triggeredBy) {
        Long id = jdbc.queryForObject("""
                insert into app.job_runs (job_name, started_at, finished_at, outcome, summary, triggered_by)
                values (:job, :started, :finished, :outcome, cast(:summary as jsonb), :by)
                returning id
                """, new MapSqlParameterSource("job", jobName)
                .addValue("started", Timestamp.from(startedAt))
                .addValue("finished", finishedAt == null ? null : Timestamp.from(finishedAt))
                .addValue("outcome", outcome.name())
                .addValue("summary", JsonColumns.write(summary))
                .addValue("by", triggeredBy), Long.class);
        if (id == null) {
            throw new IllegalStateException("insert into app.job_runs returned no id");
        }
        return id;
    }

    public void finish(long id, Instant finishedAt, JobOutcome outcome, Map<String, Object> summary) {
        jdbc.update("""
                update app.job_runs set finished_at = :finished, outcome = :outcome, summary = cast(:summary as jsonb)
                where id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("finished", Timestamp.from(finishedAt))
                .addValue("outcome", outcome.name())
                .addValue("summary", JsonColumns.write(summary)));
    }

    /** Deletes all but the newest {@code keep} runs of {@code jobName}; returns how many went. */
    public int prune(String jobName, int keep) {
        return jdbc.update("""
                delete from app.job_runs where id in (
                    select id from app.job_runs where job_name = :job
                    order by started_at desc, id desc offset :keep)
                """, new MapSqlParameterSource("job", jobName).addValue("keep", keep));
    }

    /** The newest {@code limit} runs of {@code jobName}, newest first. */
    public List<JobRun> latest(String jobName, int limit) {
        return jdbc.query("""
                select id, job_name, started_at, finished_at, outcome, summary::text, triggered_by
                from app.job_runs where job_name = :job order by started_at desc, id desc limit :limit
                """, new MapSqlParameterSource("job", jobName).addValue("limit", limit), (rs, i) -> {
            Timestamp finished = rs.getTimestamp(4);
            return new JobRun(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                    finished == null ? null : finished.toInstant(), JobOutcome.valueOf(rs.getString(5)),
                    JsonColumns.readObject(rs.getString(6)), rs.getString(7));
        });
    }
}
