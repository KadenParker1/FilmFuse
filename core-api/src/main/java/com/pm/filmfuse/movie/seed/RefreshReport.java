package com.pm.filmfuse.movie.seed;

import com.pm.filmfuse.movie.seed.SeedReport.Outcome;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one weekly catalog refresh ({@link CatalogRefreshService}) did, step by step.
 *
 * @param outcome       how it ended ({@link Outcome}: ABORTED = token rejected, the catalog kept
 *                      failing, or the size guard; the remaining steps did not run)
 * @param abortReason   why it was aborted, else null
 * @param mainTier      re-fetching the top {@code main-tier-size} stored movies
 * @param newReleases   discover-by-year for this year and last year, quality-filtered
 * @param rejects       re-checking expired quality rejects
 * @param changes       TMDB's change list for stored movies outside the main tier
 */
public record RefreshReport(Outcome outcome, String abortReason, Step mainTier, Step newReleases, Step rejects,
                            Step changes, Duration duration) {

    /**
     * Counts of one step.
     *
     * @param pages           list pages read (discover, change list)
     * @param listed          ids the step looked at before de-duplication (the tier, list results)
     * @param candidates      distinct ids the step considered
     * @param fetched         details fetched and stored (for rejects: now passing, stored, un-rejected)
     * @param skippedFresh    details fetched within {@code refresh-after}, left alone
     * @param notFound        ids the catalog no longer knows
     * @param failed          ids (or list pages) still failing after every retry
     * @param rejectedQuality ids failing the quality bar (not stored; recorded / re-stamped as rejected)
     * @param skippedRejected ids rejected within {@code quality.reject-ttl}, not fetched
     */
    public record Step(int pages, int listed, int candidates, int fetched, int skippedFresh, int notFound, int failed,
                       int rejectedQuality, int skippedRejected) {

        public static final Step NONE = new Step(0, 0, 0, 0, 0, 0, 0, 0, 0);

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("pages", pages);
            map.put("listed", listed);
            map.put("candidates", candidates);
            map.put("fetched", fetched);
            map.put("skippedFresh", skippedFresh);
            map.put("notFound", notFound);
            map.put("failed", failed);
            map.put("rejectedQuality", rejectedQuality);
            map.put("skippedRejected", skippedRejected);
            return map;
        }

        String line() {
            return candidates + " candidates, " + fetched + " fetched, " + skippedFresh + " fresh, " + notFound
                    + " not found, " + failed + " failed, " + rejectedQuality + " rejected, " + skippedRejected
                    + " skipped (rejected earlier), " + pages + " pages";
        }
    }

    /** A run that did not start (another one was in progress). */
    public static RefreshReport notStarted(Outcome outcome) {
        return new RefreshReport(outcome, null, Step.NONE, Step.NONE, Step.NONE, Step.NONE, Duration.ZERO);
    }

    public int totalFetched() {
        return mainTier.fetched + newReleases.fetched + rejects.fetched + changes.fetched;
    }

    /** The {@code app.job_runs.summary} of this run. */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("outcome", outcome.name());
        if (abortReason != null) {
            summary.put("abortReason", abortReason);
        }
        summary.put("mainTier", mainTier.toMap());
        summary.put("newReleases", newReleases.toMap());
        summary.put("rejects", rejects.toMap());
        summary.put("changes", changes.toMap());
        summary.put("durationSeconds", duration.toSeconds());
        return summary;
    }

    public String summary() {
        return "Catalog refresh " + outcome + " in " + duration.toSeconds() + " s"
                + (abortReason == null ? "" : " (" + abortReason + ")")
                + " | main tier: " + mainTier.line() + " | new releases: " + newReleases.line()
                + " | rejects: " + rejects.line() + " | changes: " + changes.line();
    }
}
