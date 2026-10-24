package com.pm.filmfuse.admin.service;

import com.pm.filmfuse.admin.AdminProperties;
import com.pm.filmfuse.admin.dto.CatalogMetricsDTO;
import com.pm.filmfuse.admin.dto.HealthMetricsDTO;
import com.pm.filmfuse.admin.dto.MlHealthDTO;
import com.pm.filmfuse.admin.dto.UsageMetricsDTO;
import com.pm.filmfuse.admin.repository.AdminMetricsRepository;
import com.pm.filmfuse.admin.repository.AdminMetricsRepository.CatalogCounts;
import com.pm.filmfuse.admin.repository.AdminMetricsRepository.SessionCounts;
import com.pm.filmfuse.admin.repository.AdminMetricsRepository.UserCounts;
import com.pm.filmfuse.admin.repository.AdminMetricsRepository.VectorCounts;
import com.pm.filmfuse.admin.settings.AdminSettingsRegistry;
import com.pm.filmfuse.reco.ml.MlHealthProbe;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Read-only metrics for the admin portal. Each block is a few aggregate statements, cached for
 * {@code filmfuse.admin.metrics-cache-ttl} (default 30 s) so a refreshing dashboard never re-counts a
 * 150k-row catalog on every request.
 */
@Service
public class AdminMetricsService {

    public static final int RATINGS_DAYS = 30;

    private final AdminMetricsRepository repository;
    private final AdminSettingsService settings;
    private final AdminProperties properties;
    private final MlHealthProbe mlHealth;
    private final Clock clock;
    private final MetricsCache cache;

    public AdminMetricsService(AdminMetricsRepository repository, AdminSettingsService settings,
                               AdminProperties properties, MlHealthProbe mlHealth, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.properties = properties;
        this.mlHealth = mlHealth;
        this.clock = clock;
        this.cache = new MetricsCache(properties.metricsCacheTtl(), clock);
    }

    public CatalogMetricsDTO catalog() {
        return cache.get("catalog", () -> {
            CatalogCounts counts = repository.catalog();
            return new CatalogMetricsDTO(counts.total(), counts.withCertification(), counts.tiers(), counts.rejected(),
                    counts.databaseBytes(), settings.intValue(AdminSettingsRegistry.MAX_DATABASE_MB),
                    properties.maxDatabaseMbCeiling(), properties.databaseLimitMb(), clock.instant());
        });
    }

    public UsageMetricsDTO usage() {
        return cache.get("usage", () -> {
            Instant now = clock.instant();
            UserCounts users = repository.users(now);
            SessionCounts sessions = repository.sessions(now);
            return new UsageMetricsDTO(users.total(), users.new7d(), users.onboarded(),
                    repository.ratingsPerDay(now, RATINGS_DAYS), sessions.started7d(), sessions.started30d(),
                    sessions.picked7d(), sessions.picked30d(), sessions.activeGroups30d(), now);
        });
    }

    public HealthMetricsDTO health() {
        return cache.get("health", () -> {
            MlHealthProbe.MlHealth ml = mlHealth.check();
            VectorCounts vectors = repository.vectors();
            return new HealthMetricsDTO(
                    new MlHealthDTO(ml.configured(), ml.reachable(), ml.status(), ml.modelVersion(), ml.latencyMs()),
                    vectors.dirty(), vectors.dirtyOldest(), vectors.neverBuilt(), repository.openReports(),
                    clock.instant());
        });
    }
}
