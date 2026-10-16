-- Movie catalog: TMDB genres (static, seeded here) and a cache of TMDB movies.
--
-- `movies` is a cache, not a source of truth: core-api upserts every movie it receives from TMDB
-- (search, popular, details) and serves details from here while the row is fresh. ids are TMDB ids.
-- ml_reader can read both tables through the default privileges granted in V2.

create table app.genres (
    id   int primary key,
    name text not null
);

-- The 19 TMDB movie genres (GET /genre/movie/list). The order of python-ml-service's GENRE_IDS
-- is defined there, not by this table.
insert into app.genres (id, name) values
    (28, 'Action'),
    (12, 'Adventure'),
    (16, 'Animation'),
    (35, 'Comedy'),
    (80, 'Crime'),
    (99, 'Documentary'),
    (18, 'Drama'),
    (10751, 'Family'),
    (14, 'Fantasy'),
    (36, 'History'),
    (27, 'Horror'),
    (10402, 'Music'),
    (9648, 'Mystery'),
    (10749, 'Romance'),
    (878, 'Science Fiction'),
    (10770, 'TV Movie'),
    (53, 'Thriller'),
    (10752, 'War'),
    (37, 'Western');

create table app.movies (
    id            int primary key,
    title         text not null,
    release_year  int,
    -- TMDB list endpoints omit runtime; only a details fetch fills it in.
    runtime       int check (runtime is null or runtime >= 0),
    poster_path   text,
    backdrop_path text,
    overview      text,
    genre_ids     int[] not null default '{}',
    popularity    double precision,
    vote_average  double precision,
    cached_at     timestamptz not null
);

create index movies_popularity_idx on app.movies (popularity desc);
