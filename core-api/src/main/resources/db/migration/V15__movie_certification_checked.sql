-- Catalog enrichment. Movies cached from list results (search / popular / discover) carry no
-- certification or keywords - TMDB only returns release_dates and keywords on details - so with
-- a certification cap and allowUnrated = false they were never eligible and pools came up empty.
-- CatalogEnrichmentService fetches details for such movies (most popular first) and stamps
-- certification_checked_at, whether or not a certification turned up, so a movie is looked up
-- once. Null = never looked up. List upserts never touch it (nor overwrite a known certification
-- or keywords with null - see MovieUpsertRepositoryImpl).

alter table app.movies add column certification_checked_at timestamptz;

-- "Most popular movies not yet looked up" - the enrichment queue.
create index movies_unchecked_popularity_idx on app.movies (popularity desc nulls last, id)
    where certification_checked_at is null;
