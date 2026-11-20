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
BLEND_K = 100
IMPLICIT_WEIGHT = 2.0
MF_WEIGHT = 1.0  # rating points per std of the iALS score; 0 = no matrix factorisation
MF_FACTORS = 64
MF_REG = 100.0
MF_ALPHA = 40.0
MF_ITERATIONS = 8
_BLOCK = 1024
NEUTRAL_IMPLICIT = 5.0

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
        mode: str = "average",
        sum_scale: float = 1.0,
    ) -> None:
        m = _Matrix(triples, min_item_ratings)
        self.items, self.index = m.items, m.index
        # "average": weighted-average rating prediction (classic). "sum": the un-normalised
        # sum of similarity * centred rating, scaled; rewards films many liked films point to.
        # "implicit": similarity from who rated what at all (watch co-occurrence), score = sum of
        # similarity to everything the user rated; ignores the scores themselves.
        self.mode, self.sum_scale = mode, sum_scale
        n = len(self.items)
        k = max(1, min(k, n - 1)) if n > 1 else 0
        self.k = k
        self.nb_idx = np.zeros((n, k), dtype=np.int32)
        self.nb_sim = np.zeros((n, k), dtype=np.float32)
        if n < 2 or k == 0:
            return
        base = m.rated if mode == "implicit" else m.centred
        norms = np.sqrt((base**2).sum(axis=1))
        norms[norms == 0] = 1.0
        for start in range(0, n, _BLOCK):
            stop = min(start + _BLOCK, n)
            num = base[start:stop] @ base.T
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
        if self.mode == "implicit":
            evidence = sims.sum(axis=1)
            pred[known] = NEUTRAL_IMPLICIT + self.sum_scale * evidence
            ok = evidence > 0
        elif self.mode == "sum":
            pred[known[ok]] = mean + self.sum_scale * num[ok]
        else:
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


class IALS:
    """Matrix factorisation by implicit alternating least squares (Hu, Koren & Volinsky 2008),
    NumPy only. Round-6 experiments (2026-11-19, eval/experiments/mf_ials.py; owner-approved after
    a 2nd-sample check): as a bonus on top of the kNN blend, +11-12% NDCG on two MovieLens samples.

    Every rated movie has preference p = (score - 1) / 9 and confidence c = 1 + ALPHA * score/10;
    unrated movies p = 0, c = 1. Users and items alternate ridge solves (the YtY trick keeps each
    pass O(ratings * d^2 + n * d^3)). Serving folds the user in from their CURRENT ratings against
    the fixed item factors (one d x d solve), so a new rating counts at once and no latent vector
    is stored per user (item factors change on every rebuild).
    """

    def __init__(
        self,
        triples: Iterable[Triple],
        factors: int = MF_FACTORS,
        reg: float = MF_REG,
        alpha: float = MF_ALPHA,
        iterations: int = MF_ITERATIONS,
        seed: int = 0,
    ) -> None:
        triples = list(triples)
        self.items = sorted({m for _, m, _ in triples})
        self.index = {m: n for n, m in enumerate(self.items)}
        users = sorted({u for u, _, _ in triples}, key=str)
        user_index = {u: n for n, u in enumerate(users)}
        self.d, self.reg, self.alpha = factors, reg, alpha
        n_users, n_items = len(users), len(self.items)
        self.Y = np.zeros((n_items, factors))
        self.YtY = np.zeros((factors, factors))
        if not triples:
            return
        rows = np.array([user_index[u] for u, _, _ in triples], dtype=np.int64)
        cols = np.array([self.index[m] for _, m, _ in triples], dtype=np.int64)
        conf, pref = self._conf_pref(np.array([float(sc) for _, _, sc in triples]))
        rng = np.random.default_rng(seed)
        x = rng.standard_normal((n_users, factors)) * 0.01
        y = rng.standard_normal((n_items, factors)) * 0.01
        by_user = _grouped(rows, cols, conf, pref, n_users)
        by_item = _grouped(cols, rows, conf, pref, n_items)
        for _ in range(iterations):
            x = self._solve(y, by_user, n_users)
            y = self._solve(x, by_item, n_items)
        self.Y, self.YtY = y, y.T @ y

    def _conf_pref(self, scores: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        return 1.0 + self.alpha * scores / 10.0, (scores - 1.0) / 9.0

    def _solve(self, fixed: np.ndarray, groups, n: int) -> np.ndarray:
        gram = fixed.T @ fixed + self.reg * np.eye(self.d)
        out = np.zeros((n, self.d))
        for e, (idx, conf, pref) in enumerate(groups):
            if idx is None:
                continue
            f = fixed[idx]
            out[e] = np.linalg.solve(gram + (f.T * (conf - 1.0)) @ f, f.T @ (conf * pref))
        return out

    def scores(self, user_ratings: dict[int, float], item_ids: list[int]):
        """Folded-in preference per movie, z-scored among the movies the model knows (so the
        weight reads as "rating points per standard deviation"); 0 and known=False otherwise."""
        out = np.zeros(len(item_ids))
        known = np.zeros(len(item_ids), dtype=bool)
        rated = [(self.index[m], float(sc)) for m, sc in user_ratings.items() if m in self.index]
        if not rated:
            return out, known
        pos_r = np.array([p for p, _ in rated])
        conf, pref = self._conf_pref(np.array([sc for _, sc in rated]))
        y_r = self.Y[pos_r]
        a = self.YtY + (y_r.T * (conf - 1.0)) @ y_r + self.reg * np.eye(self.d)
        user = np.linalg.solve(a, y_r.T @ (conf * pref))
        pos = np.array([self.index.get(i, -1) for i in item_ids])
        known = pos >= 0
        raw = self.Y[pos[known]] @ user
        if raw.size > 1:
            sd = float(np.std(raw)) or 1.0
            out[known] = (raw - raw.mean()) / sd
        return out, known


def _grouped(key: np.ndarray, other: np.ndarray, conf: np.ndarray, pref: np.ndarray, n: int):
    """Per key (user or item): the other side's indices with their confidence and preference."""
    order = np.argsort(key, kind="stable")
    k, o, c, p = key[order], other[order], conf[order], pref[order]
    bounds = np.searchsorted(k, np.arange(n + 1))
    return [
        (o[a:b], c[a:b], p[a:b]) if b > a else (None, None, None)
        for a, b in zip(bounds[:-1], bounds[1:], strict=True)
    ]


class BlendKNN:
    """Explicit + implicit item-kNN (round-1 experiments, 2026-11-19: MovieLens NDCG@10 +22%).

    pred = explicit "sum" kNN prediction + IMPLICIT_WEIGHT * log1p(implicit evidence)
           + MF_WEIGHT * z-scored iALS preference (see IALS), where the
    implicit evidence is the summed watch-co-occurrence similarity between the movie and everything
    the user rated (people who watched what you watched also watched this). `support` and `top`
    come from the explicit model, so the blend weight and "Because you rated X" keep their meaning.
    """

    def __init__(
        self,
        triples: Iterable[Triple],
        k: int = BLEND_K,
        shrink: float = KNN_SHRINK,
        implicit_weight: float = IMPLICIT_WEIGHT,
        min_item_ratings: int = MIN_ITEM_RATINGS,
        mf_weight: float | None = None,
    ) -> None:
        triples = list(triples)
        self.mf_weight = MF_WEIGHT if mf_weight is None else mf_weight
        self.mf = IALS(triples) if self.mf_weight > 0 else None
        self.explicit = ItemKNN(
            triples, k=k, shrink=shrink, min_item_ratings=min_item_ratings, mode="sum"
        )
        self.implicit = ItemKNN(
            triples, k=k, shrink=shrink, min_item_ratings=min_item_ratings, mode="implicit"
        )
        self.items = self.explicit.items
        self.implicit_weight = implicit_weight

    def predict(
        self, user_ratings: dict[int, float], item_ids: list[int]
    ) -> tuple[np.ndarray, np.ndarray, list[int | None]]:
        pred, support, top = self.explicit.predict(user_ratings, item_ids)
        _, evidence, _ = self.implicit.predict(user_ratings, item_ids)
        pred = pred + self.implicit_weight * np.log1p(evidence)
        if self.mf is not None:
            mf, _ = self.mf.scores(user_ratings, item_ids)
            pred = pred + self.mf_weight * mf
        return pred, support, top


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
    """All of `app.ratings`, rebuilt into a CF model (`factory`, default ItemKNN) in the background
    every `refresh_seconds`.
    Until the first build finishes, `model()` is None and the recommender uses content only."""

    def __init__(
        self,
        conninfo: str,
        refresh_seconds: float = 3600.0,
        background: bool = True,
        factory=None,
    ) -> None:
        self._factory = factory or ItemKNN  # triples -> CFModel
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
                model = self._factory(self._query())
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
