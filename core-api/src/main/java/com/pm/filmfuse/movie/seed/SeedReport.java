package com.pm.filmfuse.movie.seed;

import java.time.Duration;

/**
 * What one seeder run did.
 *
 * @param outcome              how it ended
 * @param pages                popular pages listed
 * @param candidates           distinct movie ids considered (popular pages and id file)
 * @param fetched              details fetched and stored (movie, content levels, checked stamp)
 * @param skippedAlreadySeeded details fetched within {@code refresh-after}, so left alone
 * @param notFound             ids the catalog does not know (cached rows are stamped checked)
 * @param failed               ids still failing after every retry (left for the next run)
 */
public record SeedReport(Outcome outcome, int pages, int candidates, int fetched, int skippedAlreadySeeded,
                         int notFound, int failed, Duration duration) {

    public enum Outcome {
        /** Every source was worked through (individual movies may still have failed). */
        COMPLETED,
        /** Stopped early: the token was rejected repeatedly, or a popular page kept failing. */
        ABORTED,
        /** The thread was interrupted (shutdown). */
        INTERRUPTED,
        /** Not started: another seed run was in progress in this process. */
        ALREADY_RUNNING
    }

    public String summary() {
        return "Catalog seed " + outcome + " in " + duration.toSeconds() + " s: " + pages + " pages, " + candidates
                + " candidates, " + fetched + " fetched, " + skippedAlreadySeeded + " already seeded, " + notFound
                + " not found, " + failed + " failed";
    }
}
