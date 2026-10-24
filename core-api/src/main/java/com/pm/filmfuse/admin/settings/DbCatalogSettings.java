package com.pm.filmfuse.admin.settings;

import com.pm.filmfuse.admin.repository.AdminSettingsRepository;
import com.pm.filmfuse.admin.repository.AdminSettingsRepository.StoredSetting;
import com.pm.filmfuse.common.job.JobNames;
import com.pm.filmfuse.movie.seed.CatalogRefreshProperties;
import com.pm.filmfuse.movie.seed.CatalogRefreshSettings;
import com.pm.filmfuse.movie.seed.CatalogSeedOverrides;
import com.pm.filmfuse.movie.seed.CatalogSeedProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The catalog jobs' runtime settings from {@code app.admin_settings}: the {@code @Primary}
 * {@link CatalogRefreshSettings} (top-up size, main tier size, pause flags - read by the jobs at the
 * start of every run and by their schedulers before every next execution) and the
 * {@link CatalogSeedOverrides} (request rate, size guard, quality bar - applied by the seeder and the
 * refresh at the start of every run). One small query each time; no cache, so a change applies to the
 * very next run. If the table cannot be read, the configured values apply (logged).
 *
 * <p>A pause only skips scheduled runs; {@code runNow} still runs. A paused job also stays paused when
 * its {@code enabled} property is true - the property decides whether this process schedules it at all.
 */
@Component
@Primary
public class DbCatalogSettings implements CatalogRefreshSettings, CatalogSeedOverrides {

    private static final Logger log = LoggerFactory.getLogger(DbCatalogSettings.class);

    private final AdminSettingsRegistry registry;
    private final AdminSettingsRepository repository;
    private final CatalogRefreshSettings.FromProperties configured;

    public DbCatalogSettings(AdminSettingsRegistry registry, AdminSettingsRepository repository,
                             CatalogSeedProperties seed, CatalogRefreshProperties refresh) {
        this.registry = registry;
        this.repository = repository;
        this.configured = new CatalogRefreshSettings.FromProperties(seed, refresh);
    }

    @Override
    public Settings current() {
        Settings base = configured.current();
        Map<String, StoredSetting> stored = stored();
        if (stored.isEmpty()) {
            return base;
        }
        int topUpPages = AdminSettingDefaults.pagesFor(longValue(stored, AdminSettingsRegistry.TOP_UP_SIZE));
        int mainTier = (int) longValue(stored, AdminSettingsRegistry.MAIN_TIER_SIZE);
        boolean topUpPaused = boolValue(stored, AdminSettingsRegistry.jobPausedKey(JobNames.CATALOG_TOP_UP));
        boolean refreshPaused = boolValue(stored, AdminSettingsRegistry.jobPausedKey(JobNames.CATALOG_REFRESH));
        return new Settings(base.topUpEnabled() && !topUpPaused, base.topUpCron(),
                stored.containsKey(AdminSettingsRegistry.TOP_UP_SIZE) ? topUpPages : base.topUpPages(),
                base.refreshEnabled() && !refreshPaused, base.refreshCron(), base.steps(),
                stored.containsKey(AdminSettingsRegistry.MAIN_TIER_SIZE) ? mainTier : base.mainTierSize(),
                base.refreshAfter(), base.newReleasePagesPerYear(), base.newReleaseMinVotes(),
                base.rejectRecheckLimit(), base.changesDays(), base.changesMaxPages(), base.maxConsecutiveFailures());
    }

    @Override
    public CatalogSeedProperties apply(CatalogSeedProperties properties) {
        Map<String, StoredSetting> stored = stored();
        if (stored.isEmpty()) {
            return properties;
        }
        double rps = stored.containsKey(AdminSettingsRegistry.REQUESTS_PER_SECOND)
                ? longValue(stored, AdminSettingsRegistry.REQUESTS_PER_SECOND) : properties.requestsPerSecond();
        int maxDb = stored.containsKey(AdminSettingsRegistry.MAX_DATABASE_MB)
                ? (int) longValue(stored, AdminSettingsRegistry.MAX_DATABASE_MB) : properties.maxDatabaseMb();
        int minVotes = stored.containsKey(AdminSettingsRegistry.QUALITY_MIN_VOTES)
                ? (int) longValue(stored, AdminSettingsRegistry.QUALITY_MIN_VOTES) : properties.quality().minVotes();
        boolean poster = stored.containsKey(AdminSettingsRegistry.QUALITY_REQUIRE_POSTER)
                ? boolValue(stored, AdminSettingsRegistry.QUALITY_REQUIRE_POSTER) : properties.quality().requirePoster();
        boolean overview = stored.containsKey(AdminSettingsRegistry.QUALITY_REQUIRE_OVERVIEW)
                ? boolValue(stored, AdminSettingsRegistry.QUALITY_REQUIRE_OVERVIEW)
                : properties.quality().requireOverview();
        return CatalogSeedOverrides.with(properties, rps, maxDb, minVotes, poster, overview);
    }

    /** Whether a job's pause flag is set (false for jobs without one). */
    public boolean isPaused(String jobName) {
        String key = AdminSettingsRegistry.jobPausedKey(jobName);
        return registry.find(key).isPresent() && boolValue(stored(), key);
    }

    private Map<String, StoredSetting> stored() {
        try {
            return repository.findAll();
        } catch (RuntimeException ex) {
            log.warn("Could not read admin settings, using the configured values: {}", ex.getMessage());
            return Map.of();
        }
    }

    private long longValue(Map<String, StoredSetting> stored, String key) {
        AdminSettingDefinition definition = registry.find(key).orElseThrow();
        StoredSetting setting = stored.get(key);
        return ((Number) definition.effective(setting == null ? null : setting.value())).longValue();
    }

    private boolean boolValue(Map<String, StoredSetting> stored, String key) {
        AdminSettingDefinition definition = registry.find(key).orElseThrow();
        StoredSetting setting = stored.get(key);
        return (Boolean) definition.effective(setting == null ? null : setting.value());
    }
}
