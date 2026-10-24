package com.pm.filmfuse.admin.dto;

import java.time.Instant;

/**
 * Catalog size and quality. Vote-count tiers are exclusive bins over {@code movies.vote_count}
 * (1000+, 100-999, 20-99, under 20, unknown) and add up to {@code totalMovies}. {@code rejected} counts
 * {@code catalog_seed_rejects}. {@code databaseBytes} is {@code pg_database_size}; {@code maxDatabaseMb}
 * is the effective size guard (setting {@code catalog.max-database-mb}), {@code maxDatabaseMbCeiling}
 * the highest an admin may set it, {@code planLimitMb} the plan's limit (500 MB on Supabase's free
 * tier). Mirrored by hand in client/types/api.ts.
 */
public record CatalogMetricsDTO(
        long totalMovies,
        long withCertification,
        VoteTiersDTO voteTiers,
        long rejected,
        long databaseBytes,
        long maxDatabaseMb,
        long maxDatabaseMbCeiling,
        long planLimitMb,
        Instant generatedAt) {
}
