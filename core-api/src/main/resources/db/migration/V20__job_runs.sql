-- Job-run history for the (upcoming) admin metrics page (com.pm.filmfuse.common.job.JobRunRecorder):
-- one row per run of the catalog seed CLI, nightly top-up, weekly catalog refresh, catalog enrichment
-- (runs that did work or failed), closeness and weekly digest jobs, plus failed vector refreshes.
-- A run is inserted as RUNNING when it starts and updated when it ends, so a row left at RUNNING with
-- no finished_at means the process died mid-run. Every insert prunes the job's history to the newest
-- filmfuse.jobs.history.keep-per-job runs (default 200), so the table stays small.
-- triggered_by: null = the scheduler; otherwise who started it ('cli', later 'admin:<user id>').

create table app.job_runs (
    id           bigint generated always as identity primary key,
    job_name     text        not null check (length(job_name) between 1 and 64),
    started_at   timestamptz not null,
    finished_at  timestamptz,
    outcome      text        not null check (outcome in ('RUNNING', 'COMPLETED', 'ABORTED', 'INTERRUPTED',
                                                         'FAILED', 'SKIPPED')),
    summary      jsonb       not null default '{}'::jsonb,
    triggered_by text check (triggered_by is null or length(triggered_by) <= 200)
);

create index job_runs_job_started_idx on app.job_runs (job_name, started_at desc);

-- Least privilege (as in V16 / V18 / V19): operational bookkeeping is no recommendation signal, and
-- triggered_by may name an admin.
revoke select on app.job_runs from ml_reader;
