package com.pm.filmfuse.admin.repository;

import com.pm.filmfuse.admin.dto.JobRunDTO;
import com.pm.filmfuse.common.job.JobOutcome;
import com.pm.filmfuse.common.json.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read side of {@code app.job_runs} for the admin portal (V20; written by {@code JobRunRecorder}). */
@Repository
public class AdminJobRunRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminJobRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest {@code perJob} runs of every job, newest first, in one query (a window over the job index). */
    public Map<String, List<JobRunDTO>> recentByJob(int perJob) {
        Map<String, List<JobRunDTO>> byJob = new LinkedHashMap<>();
        jdbc.query("""
                select id, job_name, started_at, finished_at, outcome, summary::text as summary, triggered_by
                from (select r.*, row_number() over (partition by job_name order by started_at desc, id desc) as n
                      from app.job_runs r) ranked
                where n <= :perJob
                order by job_name, started_at desc, id desc
                """, new MapSqlParameterSource("perJob", perJob), rs -> {
            byJob.computeIfAbsent(rs.getString("job_name"), name -> new ArrayList<>()).add(map(rs));
        });
        return byJob;
    }

    /** The newest run of {@code jobName} started by {@code triggeredBy} at or after {@code since}. */
    public Optional<JobRunDTO> latestStartedBy(String jobName, String triggeredBy, Instant since) {
        return jdbc.query("""
                select id, job_name, started_at, finished_at, outcome, summary::text as summary, triggered_by
                from app.job_runs
                where job_name = :job and triggered_by = :by and started_at >= :since
                order by started_at desc, id desc limit 1
                """, new MapSqlParameterSource("job", jobName).addValue("by", triggeredBy)
                .addValue("since", Timestamp.from(since)), (rs, row) -> map(rs)).stream().findFirst();
    }

    private static JobRunDTO map(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new JobRunDTO(rs.getLong("id"), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(), JobOutcome.valueOf(rs.getString("outcome")),
                JsonColumns.readObject(rs.getString("summary")), rs.getString("triggered_by"));
    }
}
