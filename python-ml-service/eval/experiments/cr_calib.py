"""Calibrated "For you" lists (Steck 2018, "Calibrated Recommendations").

The served list is re-ranked greedily so its genre mix q matches the user's own history p:

    pick c maximising  (1 - lam) * s(c) - lam * KL(p || q~(list + c))
    q~ = (1 - ALPHA) * q + ALPHA * p          (ALPHA = 0.01, Steck's smoothing)

p(g | u) = sum over the user's history items i of w_i * p(g | i) / sum w_i, p(g | i) = 1 / |genres(i)|
(TMDB genres from the catalog). History = every rated movie ("rated"), or only ones rated at least
the user's mean ("liked"); "rated" is Steck's definition. q(g | list) is the same over the list.
s(c) is the predicted 1-10 score. Only the best POOL candidates take part (like MMR_POOL).

mode "replace": calibration instead of MMR; "mmr": MMR's genre-overlap penalty (DIVERSITY) is kept
and the calibration term added on top. Users without ratings (or without genre evidence) fall back
to plain MMR, so cold onboarding-only users are unchanged. Groups are unchanged.

Factories: make_l<lam*100>[_mmr|_liked] e.g. make_l30, make_l50_mmr; `make_with(**kw)`.
`miscalibration(p, list_genres)` is the metric used by the screening script.
"""

from __future__ import annotations

import numpy as np

from app.models.contract import UserRecommendation, UserRecommendationResponse
from app.services import content_recommender as cr
from app.services.catalog import InMemoryCatalog

ALPHA = 0.01


def genre_dist(rows: np.ndarray) -> np.ndarray:
    """(n, G) 0/1 genre rows -> (n, G) per-movie distributions (zero rows stay zero)."""
    s = rows.sum(axis=1, keepdims=True)
    return np.where(s > 0, rows / np.where(s > 0, s, 1), 0.0)


def kl(p: np.ndarray, q: np.ndarray, alpha: float = ALPHA) -> np.ndarray:
    """KL(p || (1-alpha) q + alpha p) along the last axis; q may be (..., G)."""
    qt = (1 - alpha) * q + alpha * p
    mask = p > 0
    with np.errstate(divide="ignore", invalid="ignore"):
        terms = np.where(mask, p * np.log(np.where(mask, p, 1) / np.where(qt > 0, qt, 1)), 0.0)
    return terms.sum(axis=-1)


def miscalibration(p: np.ndarray, list_rows: np.ndarray) -> float:
    """KL(p || q~) for one list, uniform weight per listed movie."""
    d = genre_dist(list_rows)
    d = d[d.sum(axis=1) > 0]
    if d.size == 0 or p.sum() == 0:
        return float("nan")
    q = d.mean(axis=0)
    return float(kl(p, q))


class CalibratedRecommender(cr.ContentRecommender):
    def __init__(
        self,
        catalog,
        lam: float = 0.5,
        mode: str = "replace",
        history: str = "rated",
        pool: int = 200,
        **kw,
    ) -> None:
        super().__init__(catalog, **kw)
        self.lam, self.mode, self.history, self.pool = lam, mode, history, pool

    # p(g | u) from the user's rated movies
    def user_genre_dist(self, ratings: dict[int, float] | None) -> np.ndarray | None:
        if not ratings:
            return None
        if self.history == "liked":
            mean = float(np.mean(list(ratings.values())))
            ids = [m for m, s in ratings.items() if s >= mean]
        else:
            ids = list(ratings)
        movies = self.catalog.get_many(ids)
        rows = [m.vector[: len(cr.GENRE_IDS)] > 0 for m in movies.values()]
        if not rows:
            return None
        d = genre_dist(np.array(rows, dtype=float)).sum(axis=0)
        return d / d.sum() if d.sum() > 0 else None

    def rerank(self, scored: cr.Scored, p: np.ndarray | None, k: int) -> list[int]:
        if p is None or self.lam <= 0:
            return cr._mmr(scored, k)
        order = np.argsort(-scored.predicted, kind="stable")[: self.pool]
        if order.size == 0:
            return []
        genres = np.stack([scored.movies[i].vector[: len(cr.GENRE_IDS)] > 0 for i in order]).astype(
            float
        )
        dist = genre_dist(genres)
        has = dist.sum(axis=1) > 0
        sizes = genres.sum(axis=1)
        values = scored.predicted[order]
        lam = self.lam
        acc = np.zeros(genres.shape[1])  # sum of picked movies' genre distributions
        n_acc = 0
        max_overlap = np.zeros(order.size)
        available = np.ones(order.size, dtype=bool)
        picks: list[int] = []
        for _ in range(min(k, order.size)):
            # q of list + c for every candidate c (genre-less movies leave q unchanged)
            num = acc[None, :] + dist
            cnt = n_acc + has.astype(float)
            q = np.where(cnt[:, None] > 0, num / np.where(cnt > 0, cnt, 1)[:, None], 0.0)
            kl_new = np.where(cnt > 0, kl(p[None, :], q), kl(p, np.zeros_like(p)))
            val = (1 - lam) * values - lam * kl_new
            if self.mode == "mmr":
                val = val - cr.DIVERSITY * max_overlap
            val = np.where(available, val, -np.inf)
            j = int(np.argmax(val))
            picks.append(int(order[j]))
            available[j] = False
            if has[j]:
                acc += dist[j]
                n_acc += 1
            if self.mode == "mmr":
                inter = genres @ genres[j]
                union = sizes + sizes[j] - inter
                ov = np.where(
                    (sizes > 0) & (sizes[j] > 0), inter / np.where(union > 0, union, 1), 0.0
                )
                max_overlap = np.maximum(max_overlap, ov)
        return picks

    def recommend_for_user(self, req):
        movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
        taste = self.tastes.taste(req.user_id)
        data = self.friends.data()
        trust = data.user_trust(req.user_id) if data is not None else None
        scored = self.score(req.vector, taste, movies, friend_trust=trust)
        picks = self.rerank(scored, self.user_genre_dist(taste.ratings), req.k)
        titles = self._titles(scored, picks)
        return UserRecommendationResponse(
            results=[
                UserRecommendation(
                    movie_id=scored.movies[i].movie_id,
                    score=cr._out(scored.predicted[i]),
                    explanation=cr._explain_user(scored, i, titles),
                )
                for i in picks
            ]
        )


def make_with(**kw):
    def make():
        return CalibratedRecommender(InMemoryCatalog([]), **kw)

    return make


make_l10 = make_with(lam=0.1)
make_l20 = make_with(lam=0.2)
make_l30 = make_with(lam=0.3)
make_l50 = make_with(lam=0.5)
make_l70 = make_with(lam=0.7)
make_l90 = make_with(lam=0.9)
make_l10_mmr = make_with(lam=0.1, mode="mmr")
make_l20_mmr = make_with(lam=0.2, mode="mmr")
make_l30_mmr = make_with(lam=0.3, mode="mmr")
make_l50_mmr = make_with(lam=0.5, mode="mmr")
make_l30_liked = make_with(lam=0.3, history="liked")
make_l50_liked = make_with(lam=0.5, history="liked")
