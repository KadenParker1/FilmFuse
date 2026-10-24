package com.pm.filmfuse.admin.dto;

import com.pm.filmfuse.common.job.JobOutcome;
import java.time.Instant;
import java.util.Map;

/**
 * One recorded run ({@code app.job_runs}). {@code finishedAt} is null while it runs (or if the process
 * died mid-run). {@code triggeredBy}: null = the scheduler, {@code cli} = the seed profile,
 * {@code admin:<user id>} = Run now. Mirrored by hand in client/types/api.ts.
 */
public record JobRunDTO(long id, Instant startedAt, Instant finishedAt, JobOutcome outcome,
                        Map<String, Object> summary, String triggeredBy) {
}
