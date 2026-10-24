package com.pm.filmfuse.common.job;

/** How a recorded job run ended ({@code app.job_runs.outcome}, V20 check constraint). */
public enum JobOutcome {
    /** Started and not finished yet - or the process died mid-run (no finished_at). */
    RUNNING,
    /** Worked through (individual items may still have failed; see the summary). */
    COMPLETED,
    /** Stopped early on purpose: token rejected, a list kept failing, the database size guard. */
    ABORTED,
    /** The thread was interrupted (shutdown). */
    INTERRUPTED,
    /** An unexpected exception escaped the job. */
    FAILED,
    /** Not started: another catalog job held the lock (or one was already running in this process). */
    SKIPPED
}
