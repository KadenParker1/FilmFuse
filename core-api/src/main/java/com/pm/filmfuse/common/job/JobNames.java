package com.pm.filmfuse.common.job;

/** The {@code app.job_runs.job_name} values - stable identifiers the admin metrics page will filter by. */
public final class JobNames {

    /** The one-off full seed ({@code seed} profile, {@code CatalogSeedRunner}). */
    public static final String CATALOG_SEED = "catalog-seed";
    /** The nightly popular-pages top-up. */
    public static final String CATALOG_TOP_UP = "catalog-top-up";
    /** The weekly catalog refresh ({@code CatalogRefreshJob}). */
    public static final String CATALOG_REFRESH = "catalog-refresh";
    /** Background certification / keyword enrichment (runs that did work or failed). */
    public static final String CATALOG_ENRICHMENT = "catalog-enrichment";
    /** Interest-vector refresh - far too frequent to keep every run, so failures only. */
    public static final String VECTOR_REFRESH = "vector-refresh";
    public static final String CLOSENESS = "closeness";
    public static final String WEEKLY_DIGEST = "weekly-digest";

    private JobNames() {
    }
}
