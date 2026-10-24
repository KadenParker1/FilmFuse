package com.pm.filmfuse.admin.dto;

/**
 * {@code 202} for {@code POST /admin/jobs/{name}/run}: the run started in the background. {@code runId}
 * is its {@code app.job_runs} id once it is recorded (null if that took longer than the request waits);
 * follow it in {@code GET /admin/jobs}. Mirrored by hand in client/types/api.ts.
 */
public record JobRunStartedDTO(String jobName, Long runId, String triggeredBy) {
}
