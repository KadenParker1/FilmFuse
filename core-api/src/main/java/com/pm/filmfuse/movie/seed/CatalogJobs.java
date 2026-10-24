package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.common.job.JobOutcome;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One programmatic entry point for the catalog freshness jobs, by job name - what the admin API (a
 * later branch) will call. Runs synchronously on the caller's thread (a refresh can take tens of
 * minutes), under the cross-process catalog lock, and records the run with {@code triggeredBy}.
 */
@Component
public class CatalogJobs {

    /** Job names {@link #runNow} accepts. */
    public static final List<String> RUNNABLE = List.of(JobNames.CATALOG_TOP_UP, JobNames.CATALOG_REFRESH);

    /** How a manual run ended: {@code SKIPPED} when another catalog job held the lock. */
    public record Result(String jobName, JobOutcome outcome, Map<String, Object> summary) {
    }

    private final CatalogTopUpJob topUp;
    private final CatalogRefreshJob refresh;

    public CatalogJobs(CatalogTopUpJob topUp, CatalogRefreshJob refresh) {
        this.topUp = topUp;
        this.refresh = refresh;
    }

    /**
     * @param jobName     {@value JobNames#CATALOG_TOP_UP} or {@value JobNames#CATALOG_REFRESH}
     * @param triggeredBy who asked, e.g. {@code admin:<user id>} (stored in the job history)
     * @throws IllegalArgumentException for any other job name
     */
    public Result runNow(String jobName, String triggeredBy) {
        if (JobNames.CATALOG_TOP_UP.equals(jobName)) {
            SeedReport report = topUp.runNow(triggeredBy);
            return new Result(jobName, report.outcome().toJobOutcome(), report.toSummary());
        }
        if (JobNames.CATALOG_REFRESH.equals(jobName)) {
            RefreshReport report = refresh.runNow(triggeredBy);
            return new Result(jobName, report.outcome().toJobOutcome(), report.toSummary());
        }
        throw new IllegalArgumentException("Unknown catalog job: " + jobName + " (expected one of " + RUNNABLE + ")");
    }
}
