"""Recency in the collaborative part: recent ratings count more (time-decayed BlendKNN).

Every rating gets a weight w = max(FLOOR, 0.5 ** (age_days / half_life)), where age is measured
from that USER's newest rating (taste drift: "what you rated lately" vs "years ago"), or from the
newest rating in the whole dataset (`clock="global"`: the era a co-rating comes from).

* Similarity build (`sim_half`): the item x user matrices are scaled by w before the cosine, so a
  co-rating counts w_ui * w_uj (old pairs of ratings say less about how two movies relate today).
  Co-counts for the shrinkage are weighted too. Applies to both the explicit (centred ratings)
  and implicit (who-rated-what) halves of BlendKNN.
* Predict time (`user_half`): the user's own centred ratings / rated flags are scaled by w before
  the neighbour sum, so recently rated neighbours drive the prediction (support uses the
  weighted flags too, so beta also leans on recent evidence). The user's mean is unweighted.

half_life None = off. Offline the timestamps come from `train_on`'s (movie, score, timestamp)
triples; the user's own timestamps travel with their ratings dict (`TimedRatings`, a dict
subclass) so `predict(user_ratings, items)` keeps its signature. Live, `app.ratings.created_at`
would feed both (PostgresRatingsSource and the taste store would need to select it).

Factories: make_s<days>, make_u<days>, make_su<days> (both), make_with(**kw).
"""

from __future__ import annotations

from collections import defaultdict

import numpy as np

from app.services import collaborative as cf
from app.services import content_recommender as cr
from app.services.catalog import InMemoryCatalog, InMemoryTasteStore

DAY = 86400.0


def decay(age_s: np.ndarray | float, half: float | None, floor: float) -> np.ndarray | float:
    if half is None:
        return np.ones_like(age_s) if isinstance(age_s, np.ndarray) else 1.0
    return np.maximum(floor, 0.5 ** (np.asarray(age_s) / DAY / half))


class TimedRatings(dict):
    """movie -> score, plus .ts: movie -> unix seconds."""

    ts: dict[int, float]


class _WMatrix:
    def __init__(self, quads, min_item_ratings, half, floor, clock):
        by_user: dict[object, list[tuple[int, float, float]]] = defaultdict(list)
        counts: dict[int, int] = defaultdict(int)
        for user, item, score, ts in quads:
            by_user[user].append((item, float(score), float(ts)))
            counts[item] += 1
        self.items = sorted(i for i, c in counts.items() if c >= min_item_ratings)
        self.index = {item: n for n, item in enumerate(self.items)}
        users = list(by_user)
        newest_all = max((t for ev in by_user.values() for _, _, t in ev), default=0.0)
        self.centred = np.zeros((len(self.items), len(users)), dtype=np.float32)
        self.rated = np.zeros((len(self.items), len(users)), dtype=np.float32)
        self.wrated = np.zeros((len(self.items), len(users)), dtype=np.float32)
        for u, user in enumerate(users):
            ev = by_user[user]
            mean = sum(s for _, s, _ in ev) / len(ev)
            newest = newest_all if clock == "global" else max(t for _, _, t in ev)
            for item, score, ts in ev:
                row = self.index.get(item)
                if row is not None:
                    w = float(decay(newest - ts, half, floor))
                    self.centred[row, u] = (score - mean) * w
                    self.rated[row, u] = 1.0
                    self.wrated[row, u] = w


class TimeItemKNN(cf.ItemKNN):
    def __init__(
        self,
        m: _WMatrix,
        k: int,
        shrink: float,
        mode: str,
        user_half: float | None,
        floor: float,
        sum_scale: float = 1.0,
    ) -> None:
        self.items, self.index = m.items, m.index
        self.mode, self.sum_scale = mode, sum_scale
        self.user_half, self.floor = user_half, floor
        n = len(self.items)
        k = max(1, min(k, n - 1)) if n > 1 else 0
        self.k = k
        self.nb_idx = np.zeros((n, k), dtype=np.int32)
        self.nb_sim = np.zeros((n, k), dtype=np.float32)
        if n < 2 or k == 0:
            return
        base = m.wrated if mode == "implicit" else m.centred
        norms = np.sqrt((base**2).sum(axis=1))
        norms[norms == 0] = 1.0
        for start in range(0, n, cf._BLOCK):
            stop = min(start + cf._BLOCK, n)
            num = base[start:stop] @ base.T
            co = m.wrated[start:stop] @ m.wrated.T
            sim = num / (norms[start:stop, None] * norms[None, :]) * (co / (co + shrink))
            sim[np.arange(stop - start), np.arange(start, stop)] = 0.0
            top = np.argpartition(-np.abs(sim), k - 1, axis=1)[:, :k]
            self.nb_idx[start:stop] = top
            self.nb_sim[start:stop] = np.take_along_axis(sim, top, axis=1)

    def predict(self, user_ratings, item_ids):
        n_out = len(item_ids)
        pred = np.zeros(n_out)
        support = np.zeros(n_out)
        top: list[int | None] = [None] * n_out
        if not user_ratings or not self.items or self.k == 0:
            return pred, support, top
        mean, centred, has = cf._user_vector(self.index, len(self.items), user_ratings)
        ts = getattr(user_ratings, "ts", None)
        if self.user_half is not None and ts:
            newest = max(ts.values())
            w = np.ones(len(self.items), dtype=np.float32)
            for item, t in ts.items():
                row = self.index.get(item)
                if row is not None:
                    w[row] = decay(newest - t, self.user_half, self.floor)
            centred = centred * w
            has = has * w
        pred[:] = mean
        pos = np.array([self.index.get(i, -1) for i in item_ids])
        known = np.flatnonzero(pos >= 0)
        if known.size == 0:
            return pred, support, top
        nb = self.nb_idx[pos[known]]
        sims = self.nb_sim[pos[known]] * has[nb]
        den = np.abs(sims).sum(axis=1)
        contrib = sims * centred[nb]
        num = contrib.sum(axis=1)
        ok = den > 0
        if self.mode == "implicit":
            evidence = sims.sum(axis=1)
            pred[known] = cf.NEUTRAL_IMPLICIT + self.sum_scale * evidence
            ok = evidence > 0
        else:
            pred[known[ok]] = mean + self.sum_scale * num[ok]
        support[known] = den
        liked = np.where((centred[nb] > 0) & (sims > 0), contrib, 0.0)
        best = liked.argmax(axis=1)
        for row, out in enumerate(known):
            if ok[row] and liked[row, best[row]] > 0:
                top[out] = self.items[nb[row, best[row]]]
        return np.clip(pred, 1.0, 10.0), support, top


class TimeBlendKNN(cf.BlendKNN):
    def __init__(
        self,
        quads,
        sim_half: float | None = None,
        user_half: float | None = None,
        floor: float = 0.2,
        clock: str = "user",
        k: int = cf.BLEND_K,
        shrink: float = cf.KNN_SHRINK,
        implicit_weight: float = cf.IMPLICIT_WEIGHT,
        min_item_ratings: int = cf.MIN_ITEM_RATINGS,
    ) -> None:
        m = _WMatrix(list(quads), min_item_ratings, sim_half, floor, clock)
        self.explicit = TimeItemKNN(m, k, shrink, "sum", user_half, floor)
        self.implicit = TimeItemKNN(m, k, shrink, "implicit", user_half, floor)
        self.items = self.explicit.items
        self.implicit_weight = implicit_weight


class RecencyRecommender(cr.ContentRecommender):
    def __init__(self, catalog, cf_kw: dict | None = None, **kw) -> None:
        super().__init__(catalog, **kw)
        self.cf_kw = cf_kw or {}

    def train_on(self, train) -> None:
        super_cf, cr.CF_MODEL = cr.CF_MODEL, ""  # skip the stock CF build
        try:
            super().train_on(train)
        finally:
            cr.CF_MODEL = super_cf
        # Attach timestamps to each user's ratings dict.
        store: InMemoryTasteStore = self.tastes
        for user_id, events in train.items():
            taste = store.taste(user_id)
            timed = TimedRatings(taste.ratings)
            timed.ts = {m: float(t) for m, _, t in events}
            taste.ratings = timed
        quads = [(u, m, s, t) for u, ev in train.items() for m, s, t in ev]
        self.ratings = cf.StaticSource(TimeBlendKNN(quads, **self.cf_kw))


def make_with(**cf_kw):
    def make():
        return RecencyRecommender(InMemoryCatalog([]), cf_kw=cf_kw)

    return make


make_plain = make_with()  # sanity: should equal hybrid-v5
make_s365 = make_with(sim_half=365.0)
make_u365 = make_with(user_half=365.0)
make_u90 = make_with(user_half=90.0)
make_su365 = make_with(sim_half=365.0, user_half=365.0)
make_u3000 = make_with(user_half=3000.0)
make_gs3000f5 = make_with(sim_half=3000.0, clock="global", floor=0.5)
