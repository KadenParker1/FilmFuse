-- Phase 3 content comfort data: the US certification and TMDB keywords on cached movies, and
-- per-dimension content levels derived from them.
--
-- Vocabulary is the ML contract's (python-ml-service app/models/contract.py), because ml_reader
-- reads these tables directly: certifications are "G" / "PG" / "PG-13" / "R" / "NC-17"; content
-- dimensions and levels are lowercase. ml_reader can read the new table through the default
-- privileges granted in V2.

-- Null = unknown (not fetched yet, or TMDB has no usable US certification). Only a details fetch
-- (append_to_response=release_dates) fills it in; list results never erase it.
alter table app.movies
    add column certification text
        constraint movies_certification_check
        check (certification is null or certification in ('G', 'PG', 'PG-13', 'R', 'NC-17')),
    -- Lowercased TMDB keyword names (append_to_response=keywords). Empty until a details fetch.
    add column keywords text[] not null default '{}';

-- One row per (movie, dimension) whose level is known. A missing row = unknown level, which the
-- comfort filter treats conservatively (see ContentFilter in core-api).
--   source: 'keywords' - derived by KeywordContentLevelProvider (TMDB keywords + certification);
--           'manual'   - a hand correction, never overwritten by a derived value;
--           'provider' - a future licensed parental-guide dataset (ContentLevelProvider).
create table app.movie_content_levels (
    movie_id   int not null references app.movies (id) on delete cascade,
    dimension  text not null check (dimension in ('violence', 'sexual', 'language', 'drugs', 'frightening')),
    level      text not null check (level in ('none', 'mild', 'moderate', 'strong')),
    source     text not null check (source in ('keywords', 'manual', 'provider')),
    confidence real not null default 0.5 check (confidence >= 0 and confidence <= 1),
    updated_at timestamptz not null default now(),
    primary key (movie_id, dimension)
);

-- The eligible-candidate query filters on certification and orders by popularity.
create index movies_certification_popularity_idx on app.movies (certification, popularity desc);
