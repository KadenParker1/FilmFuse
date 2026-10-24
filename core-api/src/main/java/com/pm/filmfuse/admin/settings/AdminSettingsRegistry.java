package com.pm.filmfuse.admin.settings;

import com.pm.filmfuse.admin.AdminProperties;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Every key an admin may set, in display order. A key that is not here is refused (400) and never
 * stored. Defaults come from the configured properties, so a reset returns to whatever the
 * deployment configures.
 */
@Component
public class AdminSettingsRegistry {

    public static final String TOP_UP_SIZE = "catalog.top-up.size";
    public static final String MAIN_TIER_SIZE = "catalog.refresh.main-tier-size";
    public static final String QUALITY_MIN_VOTES = "catalog.quality.min-votes";
    public static final String QUALITY_REQUIRE_POSTER = "catalog.quality.require-poster";
    public static final String QUALITY_REQUIRE_OVERVIEW = "catalog.quality.require-overview";
    public static final String MAX_DATABASE_MB = "catalog.max-database-mb";
    public static final String REQUESTS_PER_SECOND = "catalog.requests-per-second";

    /** The prefix of a job's pause flag: {@code jobs.<job name>.paused}. */
    public static final String JOB_PAUSED_PREFIX = "jobs.";
    public static final String JOB_PAUSED_SUFFIX = ".paused";

    private final Map<String, AdminSettingDefinition> definitions;
    private final List<AdminSettingDefinition> ordered;

    public AdminSettingsRegistry(CatalogSeedProperties seed, AdminProperties admin, AdminSettingDefaults defaults) {
        Map<String, AdminSettingDefinition> byKey = new LinkedHashMap<>();
        for (AdminSettingDefinition definition : List.of(
                AdminSettingDefinition.integer(TOP_UP_SIZE, "Catalog", "Nightly top-up size",
                        "How many movies the nightly top-up refreshes from TMDB's popular list.", "movies",
                        100, 5_000, defaults::topUpSize),
                AdminSettingDefinition.integer(MAIN_TIER_SIZE, "Catalog", "Weekly main tier size",
                        "How many of the most popular movies the weekly refresh keeps current.", "movies",
                        1_000, 150_000, defaults::mainTierSize),
                AdminSettingDefinition.integer(MAX_DATABASE_MB, "Catalog", "Database size guard",
                        "Catalog jobs stop once the database is larger than this.", "MB",
                        AdminProperties.MIN_DATABASE_MB, admin.maxDatabaseMbCeiling(), seed::maxDatabaseMb),
                AdminSettingDefinition.integer(REQUESTS_PER_SECOND, "Catalog", "TMDB requests per second",
                        "The pace of catalog requests, retries included (TMDB allows about 50 per second).",
                        "requests/s", 1, 40, () -> Math.round(seed.requestsPerSecond())),
                AdminSettingDefinition.integer(QUALITY_MIN_VOTES, "Quality", "Minimum TMDB votes",
                        "Long-tail movies with fewer votes are not stored.", "votes",
                        0, 100, () -> seed.quality().minVotes()),
                AdminSettingDefinition.bool(QUALITY_REQUIRE_POSTER, "Quality", "Require a poster",
                        "Long-tail movies without a poster are not stored.", () -> seed.quality().requirePoster()),
                AdminSettingDefinition.bool(QUALITY_REQUIRE_OVERVIEW, "Quality", "Require an overview",
                        "Long-tail movies without an overview are not stored.",
                        () -> seed.quality().requireOverview()))) {
            byKey.put(definition.key(), definition);
        }
        for (String job : defaults.jobNames()) {
            String key = jobPausedKey(job);
            byKey.put(key, AdminSettingDefinition.bool(key, "Jobs", "Pause " + job,
                    "While paused, scheduled runs of " + job + " are skipped. Run now still works.", () -> false));
        }
        this.definitions = Map.copyOf(byKey);
        this.ordered = List.copyOf(byKey.values());
    }

    public static String jobPausedKey(String jobName) {
        return JOB_PAUSED_PREFIX + jobName + JOB_PAUSED_SUFFIX;
    }

    public List<AdminSettingDefinition> all() {
        return ordered;
    }

    public Optional<AdminSettingDefinition> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(definitions.get(key));
    }
}
