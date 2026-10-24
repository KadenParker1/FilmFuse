package com.pm.filmfuse.admin.dto;

import java.time.Instant;
import java.util.List;

/**
 * A background job as the admin portal shows it.
 *
 * @param name        the {@code app.job_runs.job_name} (e.g. {@code catalog-top-up})
 * @param scheduled   whether this process schedules it (its {@code enabled} property)
 * @param paused      the admin pause flag (catalog jobs only; a paused job skips scheduled runs)
 * @param controllable whether Run now / Pause / Resume apply (the catalog freshness jobs)
 * @param schedule    the cron expression (UTC) or interval it runs on; null when it is not scheduled at all
 * @param nextRunAt   the next scheduled start from the cron (null when not scheduled, paused, or interval-based)
 * @param running     whether a run is in progress (catalog jobs: the cross-process lock is held and the last
 *                    run has not finished)
 * @param lastRunStale the newest run is still RUNNING with no finish, but nothing is running it any more
 *                    (the process died mid-run): its real outcome is unknown
 * @param lastRun     the newest recorded run, if any
 * @param recentRuns  up to five newest runs, newest first
 *
 * Mirrored by hand in client/types/api.ts.
 */
public record AdminJobDTO(String name, String label, boolean scheduled, boolean paused, boolean controllable,
                          String schedule, Instant nextRunAt, boolean running, boolean lastRunStale,
                          JobRunDTO lastRun,
                          List<JobRunDTO> recentRuns) {
}
