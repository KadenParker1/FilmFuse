"""What the recommender knows about movies and users beyond the request body.

Requests carry only movie ids, so the model reads movie metadata (genres, keywords, votes,
popularity) and each user's liked/disliked movies through the read-only `ml_reader` role
(`ML_DATABASE_URL`). Offline evaluation and tests use the in-memory versions instead.

Every database failure is logged and degrades to "unknown": the model then ranks with what it
has and never fails a request because the database is slow or down.
"""

from __future__ import annotations

import bisect
import logging
import math
import threading
import time
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Protocol
from uuid import UUID

import numpy as np

from app.services.features import feature_vector

log = logging.getLogger(__name__)


@dataclass
class MovieFeatures:
    movie_id: int
    title: str
    year: int | None
    genre_ids: tuple[int, ...]
    keywords: frozenset[str]
    popularity: float | None
    vote_average: float | None  # 0-10 (TMDB) or 1-10 (offline: mean train score)
    vote_count: int | None
    vector: np.ndarray = field(init=False, repr=False)
    tokens: frozenset[str] = field(init=False, repr=False)
    prior: float = 0.0  # quality/popularity prior on the 1-10 score scale, set by the catalog

    def __post_init__(self) -> None:
        self.vector = feature_vector(self.genre_ids, self.keywords)
        # Keywords plus genre tokens: what "Like X, which you loved" similarity compares.
        self.tokens = self.keywords | frozenset(f"g:{g}" for g in self.genre_ids)


# ---- quality / popularity prior ----------------------------------------------------------------

# Votes needed before a movie's own average outweighs the catalog mean (Bayesian average).
TMDB_PRIOR_VOTES = 300
QUALITY_WEIGHT = 0.6  # per point of shrunk vote average above the catalog mean
POPULARITY_WEIGHT = 8.0  # slope across the catalog-wide popularity percentile (-0.5 .. +0.5)
# Obscurity penalty: films outside the most popular HEAD_PERCENTILE of the catalog lose up to
# OBSCURITY_PENALTY points, growing the further out they are. Inside the head it is 0, so among
# films people know, taste decides. The offline eval rewards pushing obscure films down
# heavily; core-api's pool is already mostly the popular head, where the slope adds ~0.1
# points and taste decides. Tuned on the MovieLens harness (2026-11-18): NDCG@10 ties the
# popularity baseline, hit rate beats it.
HEAD_PERCENTILE = 0.995
OBSCURITY_PENALTY = 30.0


class _PriorModel:
    """Turns vote stats and popularity into a prior, relative to the whole catalog."""

    def __init__(self, movies: Iterable[MovieFeatures], prior_votes: int) -> None:
        movies = list(movies)
        voted = [(m.vote_average, m.vote_count) for m in movies if m.vote_count and m.vote_average]
        total = sum(c for _, c in voted)
        self.mean = sum(a * c for a, c in voted) / total if total else 6.0
        self.prior_votes = prior_votes
        self.popularity = sorted(
            math.log1p(m.popularity) for m in movies if m.popularity is not None
        )

    def prior(self, m: MovieFeatures) -> float:
        votes = m.vote_count or 0
        avg = m.vote_average if m.vote_average is not None else self.mean
        shrunk = (votes * avg + self.prior_votes * self.mean) / (votes + self.prior_votes)
        pct = 0.0
        if m.popularity is not None and self.popularity:
            pct = bisect.bisect_left(self.popularity, math.log1p(m.popularity)) / len(
                self.popularity
            )
        obscurity = max(0.0, HEAD_PERCENTILE - pct) / HEAD_PERCENTILE
        return (
            QUALITY_WEIGHT * (shrunk - self.mean)
            + POPULARITY_WEIGHT * (pct - 0.5)
            - OBSCURITY_PENALTY * obscurity
        )


# ---- catalog --------------------------------------------------------------------------------


class Catalog(Protocol):
    def get_many(self, movie_ids: Iterable[int]) -> dict[int, MovieFeatures]: ...

    def all_ids(self) -> list[int]: ...

    def well_known(self, limit: int) -> list[MovieFeatures]:
        """Movies most people have heard of (most votes first)."""
        ...


class InMemoryCatalog:
    """Fixed set of movies. Used by tests and the offline eval (`prepare_for_eval`)."""

    def __init__(self, movies: Iterable[MovieFeatures], prior_votes: int = TMDB_PRIOR_VOTES):
        self._movies = {m.movie_id: m for m in movies}
        model = _PriorModel(self._movies.values(), prior_votes)
        for m in self._movies.values():
            m.prior = model.prior(m)
        self._by_votes = sorted(
            self._movies.values(), key=lambda m: (-(m.vote_count or 0), m.movie_id)
        )

    def get_many(self, movie_ids: Iterable[int]) -> dict[int, MovieFeatures]:
        return {i: self._movies[i] for i in movie_ids if i in self._movies}

    def all_ids(self) -> list[int]:
        return list(self._movies)

    def well_known(self, limit: int) -> list[MovieFeatures]:
        return self._by_votes[:limit]


_MOVIE_COLUMNS = (
    "id, title, release_year, genre_ids, keywords, popularity, vote_average, vote_count"
)


def _row_to_movie(row: tuple) -> MovieFeatures:
    movie_id, title, year, genre_ids, keywords, popularity, vote_average, vote_count = row
    return MovieFeatures(
        movie_id=movie_id,
        title=title,
        year=year,
        genre_ids=tuple(genre_ids or ()),
        keywords=frozenset(keywords or ()),
        popularity=popularity,
        vote_average=vote_average,
        vote_count=vote_count,
    )


class PostgresCatalog:
    """`app.movies` held in memory (~74k rows, a few seconds to load). `warm()` loads it in the
    background at startup; if a request arrives first, it waits for that one load. Every
    `refresh_seconds` it is reloaded in the background while requests keep using the old copy.
    Any id not loaded yet (a movie cached after the last load) is fetched on demand."""

    def __init__(
        self,
        conninfo: str,
        refresh_seconds: float = 6 * 3600,
        background_refresh: bool = True,
    ) -> None:
        self._conninfo = conninfo
        self._refresh_seconds = refresh_seconds
        self._background_refresh = background_refresh
        self._lock = threading.Lock()  # guards the fields below
        self._load_lock = threading.Lock()  # one load at a time
        self._inner: InMemoryCatalog | None = None
        self._prior: _PriorModel | None = None
        self._loaded_at = 0.0
        self._refreshing = False
        self._extra: dict[int, MovieFeatures] = {}
        self._absent: set[int] = set()

    def _connect(self):  # pragma: no cover - exercised against a real database
        import psycopg

        return psycopg.connect(self._conninfo, connect_timeout=5)

    def _query(self, sql: str, params: tuple = ()) -> list[tuple]:
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()

    def _load(self, only_if_missing: bool = False) -> None:
        with self._load_lock:
            if only_if_missing and self._inner is not None:
                return  # a load (e.g. the warm-up) finished while we waited
            try:
                rows = self._query(f"select {_MOVIE_COLUMNS} from app.movies")
                movies = [_row_to_movie(r) for r in rows]
                inner, prior = InMemoryCatalog(movies), _PriorModel(movies, TMDB_PRIOR_VOTES)
                with self._lock:
                    self._inner, self._prior, self._extra = inner, prior, {}
                    self._absent = set()
                log.info("catalog loaded: %d movies", len(movies))
            except Exception:
                log.exception("catalog load failed; ranking without movie metadata")
                with self._lock:
                    if self._inner is None:
                        self._inner = InMemoryCatalog([])
                        self._prior = _PriorModel([], TMDB_PRIOR_VOTES)
            finally:
                # On failure keep the old data and retry after the next interval.
                with self._lock:
                    self._loaded_at = time.monotonic()
                    self._refreshing = False

    def warm(self) -> None:
        """Start the first load in the background (called at app startup)."""
        threading.Thread(
            target=self._load, kwargs={"only_if_missing": True}, name="catalog-warm", daemon=True
        ).start()

    def _ensure_loaded(self) -> InMemoryCatalog:
        with self._lock:
            inner = self._inner
            stale = time.monotonic() - self._loaded_at > self._refresh_seconds
            start_refresh = inner is not None and stale and not self._refreshing
            if start_refresh:
                self._refreshing = True
        if inner is None:
            self._load(only_if_missing=True)  # waits for a warm-up already running
            with self._lock:
                assert self._inner is not None
                return self._inner
        if start_refresh:
            if self._background_refresh:
                threading.Thread(target=self._load, name="catalog-refresh", daemon=True).start()
            else:
                self._load()
                with self._lock:
                    inner = self._inner
        return inner

    def get_many(self, movie_ids: Iterable[int]) -> dict[int, MovieFeatures]:
        ids = list(movie_ids)
        inner = self._ensure_loaded()
        found = inner.get_many(ids)
        found.update({i: self._extra[i] for i in ids if i in self._extra})
        # Ids looked up before and not in app.movies aren't asked for again until the next reload.
        missing = [i for i in ids if i not in found and i not in self._absent]
        if missing:
            try:
                rows = self._query(
                    f"select {_MOVIE_COLUMNS} from app.movies where id = any(%s)", (missing,)
                )
                failed = False
            except Exception:
                log.exception("catalog lookup of %d new movies failed", len(missing))
                rows, failed = [], True
            with self._lock:
                if not failed:
                    self._absent.update(set(missing) - {r[0] for r in rows})
                for row in rows:
                    m = _row_to_movie(row)
                    if self._prior is not None:
                        m.prior = self._prior.prior(m)
                    self._extra[m.movie_id] = m
                    found[m.movie_id] = m
        return found

    def all_ids(self) -> list[int]:
        return self._ensure_loaded().all_ids()

    def well_known(self, limit: int) -> list[MovieFeatures]:
        return self._ensure_loaded().well_known(limit)


# ---- per-user taste evidence ----------------------------------------------------------------

LOVED_MIN_SCORE = 8
DISLIKED_MAX_SCORE = 3


@dataclass
class UserTaste:
    """Item-level evidence for one user. `loved` is ordered strongest first."""

    loved: list[int] = field(default_factory=list)
    disliked: set[int] = field(default_factory=set)  # low ratings + least favourites
    dismissed: set[int] = field(default_factory=set)  # "not interested" on a recommendation
    n_signals: int = 0  # ratings + favourites + least favourites: how much we know
    ratings: dict[int, float] = field(default_factory=dict)  # every rating (collaborative part)


EMPTY_TASTE = UserTaste()


class TasteStore(Protocol):
    def taste(self, user_id: UUID) -> UserTaste: ...


class InMemoryTasteStore:
    def __init__(self, tastes: dict[UUID, UserTaste] | None = None) -> None:
        self._tastes = dict(tastes or {})

    def put(self, user_id: UUID, taste: UserTaste) -> None:
        self._tastes[user_id] = taste

    def taste(self, user_id: UUID) -> UserTaste:
        return self._tastes.get(user_id, EMPTY_TASTE)


class NullTasteStore:
    def taste(self, user_id: UUID) -> UserTaste:
        return EMPTY_TASTE


# Favourites, least favourites, dismissals and the user's most recent ratings.
_TASTE_SQL = """
select 'fav' as kind, movie_id, 10 as score, rank as ord
  from app.user_favorite_movies where user_id = %(u)s
union all
select 'least', movie_id, 1, rank from app.user_least_favorite_movies where user_id = %(u)s
union all
select 'dismiss', movie_id, 0, 0 from app.rec_dismissals where user_id = %(u)s
union all
select 'rating', movie_id, score, 0 from (
  select movie_id, score from app.ratings where user_id = %(u)s
  order by created_at desc limit 2000
) r
"""

MAX_LOVED = 40


def taste_from_rows(rows: Iterable[tuple]) -> UserTaste:
    favs: list[tuple[int, int]] = []
    rated: list[tuple[int, int]] = []
    taste = UserTaste()
    for kind, movie_id, score, ord_ in rows:
        if kind == "fav":
            favs.append((ord_, movie_id))
            taste.n_signals += 1
        elif kind == "least":
            taste.disliked.add(movie_id)
            taste.n_signals += 1
        elif kind == "dismiss":
            taste.dismissed.add(movie_id)
        else:
            taste.n_signals += 1
            taste.ratings[movie_id] = float(score)
            if score >= LOVED_MIN_SCORE:
                rated.append((score, movie_id))
            elif score <= DISLIKED_MAX_SCORE:
                taste.disliked.add(movie_id)
    rated.sort(key=lambda sm: -sm[0])  # stable: equal scores keep the query's newest-first order
    loved = [m for _, m in sorted(favs)] + [m for _, m in rated]
    seen: set[int] = set()
    taste.loved = [m for m in loved if not (m in seen or seen.add(m))][:MAX_LOVED]
    return taste


class PostgresTasteStore:
    def __init__(self, conninfo: str) -> None:
        self._conninfo = conninfo

    def _query(self, user_id: UUID) -> list[tuple]:  # pragma: no cover - real database only
        import psycopg

        with psycopg.connect(self._conninfo, connect_timeout=5) as conn, conn.cursor() as cur:
            cur.execute(_TASTE_SQL, {"u": user_id})
            return cur.fetchall()

    def taste(self, user_id: UUID) -> UserTaste:
        try:
            return taste_from_rows(self._query(user_id))
        except Exception:
            log.exception("taste lookup failed for a user; using vector only")
            return EMPTY_TASTE
