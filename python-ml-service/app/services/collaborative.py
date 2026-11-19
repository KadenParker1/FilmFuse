"""Collaborative filtering for hybrid-v1: learn from which movies people rate alike.

Two models with one interface, `predict(user_ratings, item_ids)`. It returns, per item:
* `pred`: a predicted 1-10 score (centred on the user's own mean rating);
* `support`: how much evidence backs it (0 = none, so the caller falls back to content);
* `top`: the liked movie (rated above the user's mean) that contributed most, for
  "Because you rated X 9/10".

* ItemKNN (served): adjusted-cosine similarity between movies over mean-centred ratings,
  shrunk by how many people rated both (sim * n / (n + SHRINK)), top-K neighbours per movie.
  Faithful explanations: the score is literally a weighted average over named movies.
* EASE (offline comparison): ridge regression of each movie on all others (Steck 2019), on
  mean-centred ratings, restricted to movies with enough ratings.

Only movies with >= MIN_ITEM_RATINGS ratings take part. Dense NumPy is fine at FilmFuse's size
(thousands of rated movies, hundreds of users); revisit with sparse matrices past ~20k movies.

`PostgresRatingsSource` loads `app.ratings` through the read-only `ml_reader` role and rebuilds the
model in the background (same pattern as `PostgresCatalog`). A user's own, latest ratings come
from the taste store on every request, so a new rating counts immediately; only the movie-to-movie
similarities wait for the next rebuild.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import defaultdict
from collections.abc import Iterable
from typing import Protocol

import numpy as np

log = logging.getLogger(__name__)

MIN_ITEM_RATINGS = 3
KNN_K = 50
KNN_SHRINK = 10.0
EASE_LAMBDA = 200.0
_BLOCK = 1024

Triple = tuple[object, int, float]  # user id, movie id, score 1-10


class CFModel(Protocol):
    def predict(
        self, user_ratings: dict[int, float], item_ids: list[int]
    ) -> tuple[np.ndarray, np.ndarray, list[int | None]]: ...


class _Matrix:
    """Mean-centred ratings, items x users, for items with enough ratings."""

    def __init__(self, triples: Iterable[Triple], min_item_ratings: int) -> None:
        by_user: dict[object, list[tuple[int, float]]] = defaultdict(list)
        counts: dict[int, int] = defaultdict(int)
        for user, item, score in triples:
            by_user[user].append((item, float(score)))
            counts[item] += 1
        self.items = sorted(i for i, c in counts.items() if c >= min_item_ratings)
        self.index = {item: n for n, item in enumerate(self.items)}
        users = list(by_user)
        self.centred = np.zeros((len(self.items), len(users)), dtype=np.float32)
        self.rated = np.zeros((len(self.items), len(users)), dtype=np.float32)
        for u, user in enumerate(users):
            ratings = by_user[user]
            mean = sum(s for _, s in ratings) / len(ratings)
            for item, score in ratings:
                row = self.index.get(item)
                if row is not None:
                    self.centred[row, u] = score - mean
                    self.rated[row, u] = 1.0


def _user_vector(index: dict[int, int], n: int, user_ratings: dict[int, float]):
    """The user's mean, and their centred ratings / rated flags over the model's items."""
    mean = float(np.mean(list(user_ratings.values()))) if user_ratings else 0.0
    centred = np.zeros(n, dtype=np.float32)
    has = np.zeros(n, dtype=np.float32)
    for item, score in user_ratings.items():
        row = index.get(item)
        if row is not None:
            centred[row] = score - mean
            has[row] = 1.0
    return mean, centred, has


class ItemKNN:
    def __init__(
        self,
        triples: Iterable[Triple],
        k: int = KNN_K,
        shrink: float = KNN_SHRINK,
        min_item_ratings: int = MIN_ITEM_RATINGS,
    ) -> None:
        m = _Matrix(triples, min_item_ratings)
        self.items, self.index = m.items, m.index
        n = len(self.items)
        k = max(1, min(k, n - 1)) if n > 1 else 0
        self.k = k
        self.nb_idx = np.zeros((n, k), dtype=np.int32)
        self.nb_sim = np.zeros((n, k), dtype=np.float32)
        if n < 2 or k == 0:
            return
        norms = np.sqrt((m.centred**2).sum(axis=1))
        norms[norms == 0] = 1.0
        for start in range(0, n, _BLOCK):
            stop = min(start + _BLOCK, n)
            num = m.centred[start:stop] @ m.centred.T
            co = m.rated[start:stop] @ m.rated.T
            sim = num / (norms[start:stop, None] * norms[None, :]) * (co / (co + shrink))
            sim[np.arange(stop - start), np.arange(start, stop)] = 0.0  # not its own neighbour
            top = np.argpartition(-np.abs(sim), k - 1, axis=1)[:, :k]
            self.nb_idx[start:stop] = top
            self.nb_sim[start:stop] = np.take_along_axis(sim, top, axis=1)

    def predict(
        self, user_ratings: dict[int, float], item_ids: list[int]
    ) -> tuple[np.ndarray, np.ndarray, list[int | None]]:
        n_out = len(item_ids)
        pred = np.zeros(n_out)
        support = np.zeros(n_out)
        top: list[int | None] = [None] * n_out
        if not user_ratings or not self.items or self.k == 0:
            return pred, support, top
        mean, centred, has = _user_vector(self.index, len(self.items), user_ratings)
        pred[:] = mean
        pos = np.array([self.index.get(i, -1) for i in item_ids])
        known = np.flatnonzero(pos >= 0)
        if known.size == 0:
            return pred, support, top
        nb = self.nb_idx[pos[known]]  # (m, K)
        sims = self.nb_sim[pos[known]] * has[nb]  # only neighbours the user rated
        den = np.abs(sims).sum(axis=1)
        contrib = sims * centred[nb]
        num = contrib.sum(axis=1)
        ok = den > 0
        pred[known[ok]] = mean + num[ok] / den[ok]
        support[known] = den
        # Name a liked neighbour (rated above the user's mean, positively similar): "Because you
        # rated X 9/10" reads right; a hated, opposite movie is true but confusing.
        liked = np.where((centred[nb] > 0) & (sims > 0), contrib, 0.0)
        best = liked.argmax(axis=1)
        for row, out in enumerate(known):
            if ok[row] and liked[row, best[row]] > 0:
                top[out] = self.items[nb[row, best[row]]]
        return np.clip(pred, 1.0, 10.0), support, top


class EASE:
    """Offline comparison model (see module docstring)."""

    def __init__(
        self,
        triples: Iterable[Triple],
        lam: float = EASE_LAMBDA,
        min_item_ratings: int = MIN_ITEM_RATINGS,
    ) -> None:
        m = _Matrix(triples, min_item_ratings)
        self.items, self.index = m.items, m.index
        n = len(self.items)
        self.b = np.zeros((n, n), dtype=np.float32)
        if n == 0:
            return
        x = m.centred.astype(np.float64)  # items x users
        g = x @ x.T + lam * np.eye(n)
        p = np.linalg.inv(g)
        b = -p / np.diag(p)[None, :]
        np.fill_diagonal(b, 0.0)
        self.b = b.astype(np.float32)  # b[j, i]: weight of item j when predicting item i

    def predict(
        self, user_ratings: dict[int, float], item_ids: list[int]
    ) -> tuple[np.ndarray, np.ndarray, list[int | None]]:
        n_out = len(item_ids)
        pred = np.zeros(n_out)
        support = np.zeros(n_out)
        top: list[int | None] = [None] * n_out
        if not user_ratings or not self.items:
            return pred, support, top
        mean, centred, has = _user_vector(self.index, len(self.items), user_ratings)
        pred[:] = mean
        pos = np.array([self.index.get(i, -1) for i in item_ids])
        known = np.flatnonzero(pos >= 0)
        if known.size == 0:
            return pred, support, top
        cols = self.b[:, pos[known]]  # (n_items, m)
        contrib = cols * centred[:, None]
        pred[known] = mean + contrib.sum(axis=0)
        support[known] = (np.abs(cols) * has[:, None]).sum(axis=0)
        liked = np.where((centred[:, None] > 0) & (cols > 0), contrib, 0.0)
        best = liked.argmax(axis=0)
        for col, out in enumerate(known):
            if liked[best[col], col] > 0:
                top[out] = self.items[best[col]]
        return np.clip(pred, 1.0, 10.0), support, top


# ---- sources ---------------------------------------------------------------------------------


class RatingsSource(Protocol):
    def model(self) -> CFModel | None: ...


class StaticSource:
    """A fixed model (tests and the offline eval)."""

    def __init__(self, model: CFModel | None) -> None:
        self._model = model

    def model(self) -> CFModel | None:
        return self._model


class PostgresRatingsSource:
    """All of `app.ratings`, rebuilt into an ItemKNN in the background every `refresh_seconds`.
    Until the first build finishes, `model()` is None and the recommender uses content only."""

    def __init__(
        self,
        conninfo: str,
        refresh_seconds: float = 3600.0,
        background: bool = True,
    ) -> None:
        self._conninfo = conninfo
        self._refresh_seconds = refresh_seconds
        self._background = background
        self._lock = threading.Lock()
        self._build_lock = threading.Lock()
        self._model: CFModel | None = None
        self._built_at = 0.0
        self._building = False

    def _query(self) -> list[tuple]:  # pragma: no cover - real database only
        import psycopg

        with psycopg.connect(self._conninfo, connect_timeout=5) as conn, conn.cursor() as cur:
            cur.execute("select user_id, movie_id, score from app.ratings")
            return cur.fetchall()

    def _build(self) -> None:
        with self._build_lock:
            try:
                started = time.monotonic()
                model = ItemKNN(self._query())
                with self._lock:
                    self._model = model
                log.info(
                    "CF model built: %d movies in %.1fs",
                    len(model.items),
                    time.monotonic() - started,
                )
            except Exception:
                log.exception("CF model build failed; recommending from content only")
            finally:
                with self._lock:
                    self._built_at = time.monotonic()
                    self._building = False

    def warm(self) -> None:
        with self._lock:
            if self._building:
                return
            self._building = True
        threading.Thread(target=self._build, name="cf-warm", daemon=True).start()

    def model(self) -> CFModel | None:
        with self._lock:
            stale = time.monotonic() - self._built_at > self._refresh_seconds
            start = stale and not self._building
            if start:
                self._building = True
        if start:
            if self._background:
                threading.Thread(target=self._build, name="cf-refresh", daemon=True).start()
            else:
                self._build()
        with self._lock:
            return self._model
