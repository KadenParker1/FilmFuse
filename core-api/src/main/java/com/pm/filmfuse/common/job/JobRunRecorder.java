package com.pm.filmfuse.common.job;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Job-run history ({@code app.job_runs}) for the admin metrics page. A run is inserted as
 * {@link JobOutcome#RUNNING} when it starts (so a long seed shows up while it runs) and updated when it
 * ends; every insert prunes the job's history to the newest {@code filmfuse.jobs.history.keep-per-job}
 * runs (default 200). Recording is best effort: a database hiccup here is logged and never fails or
 * stops the job itself. A process that dies mid-run leaves its row at RUNNING without finished_at.
 *
 * <p>{@code triggeredBy}: null for a scheduled run, otherwise who asked ({@code "cli"} for the seed
 * profile, later e.g. {@code "admin:<user id>"}).
 */
@Service
public class JobRunRecorder {

    public static final int DEFAULT_KEEP_PER_JOB = 200;
    private static final int MAX_ERROR_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(JobRunRecorder.class);

    private final JobRunRepository runs;
    private final Clock clock;
    private final int keepPerJob;

    public JobRunRecorder(JobRunRepository runs, Clock clock,
                          @Value("${filmfuse.jobs.history.keep-per-job:" + DEFAULT_KEEP_PER_JOB + "}") int keepPerJob) {
        this.runs = runs;
        this.clock = clock;
        this.keepPerJob = Math.max(1, keepPerJob);
    }

    /** How a finished run is stored. */
    public record Finish(JobOutcome outcome, Map<String, Object> summary) {

        public Finish {
            summary = summary == null ? Map.of() : summary;
        }
    }

    /** A started run; {@code id} null when the start could not be recorded. */
    public record Handle(Long id, String jobName, String triggeredBy, Instant startedAt) {
    }

    /** Records the start of a run (RUNNING). Never throws. */
    public Handle start(String jobName, String triggeredBy) {
        Instant now = clock.instant();
        Long id = null;
        try {
            id = runs.insert(jobName, now, null, JobOutcome.RUNNING, Map.of(), triggeredBy);
            prune(jobName);
        } catch (RuntimeException ex) {
            log.warn("Could not record the start of job {}: {}", jobName, ex.getMessage());
        }
        return new Handle(id, jobName, triggeredBy, now);
    }

    /** Records how a started run ended. Never throws. */
    public void finish(Handle handle, JobOutcome outcome, Map<String, Object> summary) {
        Map<String, Object> safe = summary == null ? Map.of() : summary;
        try {
            if (handle.id() != null) {
                runs.finish(handle.id(), clock.instant(), outcome, safe);
            } else {
                runs.insert(handle.jobName(), handle.startedAt(), clock.instant(), outcome, safe, handle.triggeredBy());
                prune(handle.jobName());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not record the end of job {} ({}): {}", handle.jobName(), outcome, ex.getMessage());
        }
    }

    /** Records a whole run in one row (for jobs that only keep some runs, e.g. failures). Never throws. */
    public void recordFinished(String jobName, String triggeredBy, Instant startedAt, JobOutcome outcome,
                               Map<String, Object> summary) {
        try {
            runs.insert(jobName, startedAt, clock.instant(), outcome, summary == null ? Map.of() : summary,
                    triggeredBy);
            prune(jobName);
        } catch (RuntimeException ex) {
            log.warn("Could not record a {} run of job {}: {}", outcome, jobName, ex.getMessage());
        }
    }

    /** A run that never started because another catalog job held the lock. Never throws. */
    public void recordSkipped(String jobName, String triggeredBy, String reason) {
        Instant now = clock.instant();
        recordFinished(jobName, triggeredBy, now, JobOutcome.SKIPPED, Map.of("reason", reason));
    }

    /**
     * Runs {@code work} as one recorded run: RUNNING first, then {@code describe(result)}, or
     * {@link JobOutcome#FAILED} with the error when it throws (the exception is rethrown).
     */
    public <T> T run(String jobName, String triggeredBy, Supplier<T> work, Function<T, Finish> describe) {
        Handle handle = start(jobName, triggeredBy);
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error ex) {
            finish(handle, JobOutcome.FAILED, errorSummary(ex));
            throw ex;
        }
        Finish finish;
        try {
            finish = describe.apply(result);
        } catch (RuntimeException ex) {
            log.warn("Could not describe a run of job {}: {}", jobName, ex.getMessage());
            finish = new Finish(JobOutcome.COMPLETED, Map.of());
        }
        finish(handle, finish.outcome(), finish.summary());
        return result;
    }

    /** {@code {"error": "<exception class>: <message>"}}, the message cut to a few hundred characters. */
    public static Map<String, Object> errorSummary(Throwable ex) {
        String message = ex.getMessage() == null ? "" : ": " + ex.getMessage();
        String error = ex.getClass().getSimpleName() + message;
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH) + "...";
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("error", error);
        return summary;
    }

    private void prune(String jobName) {
        runs.prune(jobName, keepPerJob);
    }
}
