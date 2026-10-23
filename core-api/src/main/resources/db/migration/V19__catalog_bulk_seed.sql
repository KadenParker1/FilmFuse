-- Bulk catalog seeding (com.pm.filmfuse.movie.seed.CatalogSeedService: TMDB daily id export and
-- discover-by-year sources, quality filter, database size guard).

-- TMDB's vote count, from details and list results. 4 bytes a row; the seeder's quality bar
-- (filmfuse.catalog.seed.quality.min-votes) reads it, and it is a plain catalog fact, so ml_reader
-- keeps its table-level SELECT on app.movies (V2) for this column too.
alter table app.movies add column vote_count integer check (vote_count is null or vote_count >= 0);

-- Cursor versions: the export cursor (name 'export', next_page = next 1-based position in the sorted
-- id list) is only valid for the export file it was taken from (version = that file's date); the
-- discover cursor (name 'discover', next_page = year * 1000 + page) only for the same from-year /
-- pages-per-year / min-votes. The popular cursor (name 'popular') leaves it null. Each source has
-- its own row, so cursors never collide.
alter table app.catalog_seed_state add column version text;

-- Ids that failed the seeder's quality bar: not stored in app.movies, and not fetched again until
-- quality.reject-ttl has passed. Deliberately tiny (no reason column) - a large run can reject a
-- six-figure number of ids and this table counts against the database size too. No foreign key:
-- rejected movies are exactly the ones without a (seeded) app.movies row.
create table app.catalog_seed_rejects (
    movie_id    integer primary key check (movie_id > 0),
    rejected_at timestamptz not null default now()
);

-- Least privilege (as in V16 / V18): seeding bookkeeping is no recommendation signal.
revoke select on app.catalog_seed_rejects from ml_reader;
