-- Catalog seeder (com.pm.filmfuse.movie.seed.CatalogSeedService). The seeder bulk-fills app.movies
-- from the catalog's popular pages; this keeps its page cursor so a run that was stopped (killed,
-- token rejected, catalog outage) resumes where it left off instead of re-listing from page 1.
-- One row per cursor (name 'popular'); the row is deleted when a full pass completes.
-- Movie details need no state of their own: a movie whose certification_checked_at is recent is
-- skipped (see CatalogSeedService), so re-running is idempotent either way.

create table app.catalog_seed_state (
    name       text primary key,
    next_page  integer     not null check (next_page >= 1),
    updated_at timestamptz not null default now()
);

-- Least privilege (as in V16): V2's default privileges grant ml_reader SELECT on every new table;
-- seeding bookkeeping is no recommendation signal, so take it back.
revoke select on app.catalog_seed_state from ml_reader;
