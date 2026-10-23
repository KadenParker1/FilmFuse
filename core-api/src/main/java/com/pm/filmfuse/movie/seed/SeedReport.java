package com.pm.filmfuse.movie.seed;

import java.time.Duration;

/**
 * What one seeder run did.
 *
 * @param outcome              how it ended
 * @param pages                list pages read (popular and discover)
 * @param candidates           distinct movie ids considered (every source)
 * @param fetched              details fetched and stored (movie, content levels, checked stamp)
 * @param skippedAlreadySeeded details fetched within {@code refresh-after}, so left alone
 * @param notFound             ids the catalog does not know (cached rows are stamped checked)
 * @param failed               ids still failing after every retry (left for the next run)
 * @param rejectedQuality      ids that failed the quality bar this run (not stored, recorded as rejected)
 * @param skippedRejected      ids rejected by an earlier run within {@code quality.reject-ttl}, so not fetched
 */
public record SeedReport(Outcome outcome, int pages, int candidates, int fetched, int skippedAlreadySeeded,
                         int notFound, int failed, int rejectedQuality, int skippedRejected, Duration duration) {

    public enum Outcome {
        /** Every source was worked through (individual movies may still have failed). */
        COMPLETED,
        /**
         * Stopped early: the token was rejected repeatedly, a list page kept failing, the export could
         * not be read, or the database outgrew {@code max-database-mb}. Cursors are kept.
         */
        ABORTED,
        /** The thread was interrupted (shutdown). */
        INTERRUPTED,
        /** Not started: another seed run was in progress in this process. */
        ALREADY_RUNNING
    }

    /** A report of a run without quality filtering. */
    public SeedReport(Outcome outcome, int pages, int candidates, int fetched, int skippedAlreadySeeded,
                      int notFound, int failed, Duration duration) {
        this(outcome, pages, candidates, fetched, skippedAlreadySeeded, notFound, failed, 0, 0, duration);
    }

    public String summary() {
        return "Catalog seed " + outcome + " in " + duration.toSeconds() + " s: " + pages + " pages, " + candidates
                + " candidates, " + fetched + " fetched, " + skippedAlreadySeeded + " already seeded, " + notFound
                + " not found, " + failed + " failed, " + rejectedQuality + " rejected (quality), " + skippedRejected
                + " skipped (rejected earlier)";
    }
}
