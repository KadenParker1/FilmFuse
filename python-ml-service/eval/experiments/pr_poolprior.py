"""Prior experiment: a quality/popularity prior relative to the CANDIDATE POOL, not the catalog.

The catalog-wide prior (catalog._PriorModel) spreads MovieLens' production-like 1000-movie pool
over several points (percentiles 0.9-1.0 under a 0.995 obscurity head) but is nearly flat on
core-api's live pool (top 1000 of ~74k, percentiles 0.986-1.0). Here the prior is recomputed
per request from the candidates themselves:

    head      = the HEAD_RANK most popular candidates (all of them in a 1000-movie pool)
    pct       = popularity rank percentile inside the head (0 = least, 1 = most popular)
    quality   = Bayesian vote average (prior_votes: 10 offline, 300 TMDB) minus the head's
                vote-weighted mean
    prior     = Q * quality + P * f(pct) - JUNK for candidates outside the head
    predicted = taste + alpha * (prior - median(prior)),
                alpha = warm + (cold - warm) / (1 + signals / half)

f is "pct" (pct - 0.5), "z" (z-score of log popularity inside the head) or "sqrt"/"sq"
(pct ** 0.5 / pct ** 2, centred). The catalog prior is ignored (set CAT > 0 to blend it back in).
So the ranking depends only on the pool's own spread: offline and live behave alike, and the
whole-catalog check (pool = 9.7k movies) still keeps films outside the top HEAD_RANK out.

Factories: `make` (defaults) and `make_with(**kw)` used by the named variants below.
"""

from __future__ import annotations

import math

import numpy as np

from app.services import content_recommender as cr
from app.services.catalog import InMemoryCatalog, MovieFeatures, TMDB_PRIOR_VOTES


class PoolPriorRecommender(cr.ContentRecommender):
    def __init__(
        self,
        catalog,
        q: float = 0.6,
        p: float = 2.0,
        junk: float = 30.0,
        head_rank: int = 1000,
        pop_mode: str = "pct",
        warm: float = 1.0,
        cold: float = 2.0,
        half: float = 10.0,
        cat: float = 0.0,
        q_cold_boost: float = 0.0,
        mainstream: float = 0.0,
        t: float = 0.5,
        share_lo: float | None = None,
        share_hi: float = 1.0,
        floor_shift: float = 0.0,
    ) -> None:
        super().__init__(catalog)
        self.q, self.p, self.junk, self.head_rank, self.pop_mode = q, p, junk, head_rank, pop_mode
        self.warm, self.cold, self.half, self.cat = warm, cold, half, cat
        self.q_cold_boost = q_cold_boost
        # Popularity propensity: P is scaled from share_lo (the user rates only films less popular
        # than anything in the pool) to share_hi (only films at least as popular as the pool's
        # least popular one), by the shrunk share of their rated films in that popular range.
        self.share_lo, self.share_hi = share_lo, share_hi
        self.floor_shift = floor_shift  # count films down to e**-shift x the pool's floor
        self.t = t  # knee of the "hinge" / "top" popularity shapes
        self.mainstream = mainstream  # scale P by how mainstream the user's rated films are
        self.prior_votes = TMDB_PRIOR_VOTES

    def prepare_for_eval(self, data) -> None:
        super().prepare_for_eval(data)
        self.prior_votes = 10  # as prepare_for_eval's InMemoryCatalog

    def _pop_scale(self, head_logpop: np.ndarray, rated: dict[int, float]) -> float:
        """1 + M * (2 * mean popularity percentile of the user's rated films, against the
        head, - 1): users who rate the head get more popularity, long-tail raters less.
        Shrunk to 1 with few ratings."""
        if self.share_lo is not None:
            if len(head_logpop) == 0:
                return 1.0
            floor = float(head_logpop.min()) - self.floor_shift
            movies = self.catalog.get_many(list(rated)) if rated else {}
            n_in = sum(
                1
                for m in movies.values()
                if m.popularity is not None and math.log1p(m.popularity) >= floor
            )
            share = (n_in + 2.5) / (len(movies) + 5)
            return max(0.0, self.share_lo + (self.share_hi - self.share_lo) * share)
        if not self.mainstream or not rated or len(head_logpop) == 0:
            return 1.0
        ref = np.sort(head_logpop)
        movies = self.catalog.get_many(list(rated))
        lp = np.array(
            [math.log1p(m.popularity) for m in movies.values() if m.popularity is not None]
        )
        if len(lp) == 0:
            return 1.0
        pct = np.searchsorted(ref, lp, side="right") / len(ref)
        shrink = len(lp) / (len(lp) + 10)
        return max(0.0, 1 + self.mainstream * shrink * (2 * float(pct.mean()) - 1))

    def pool_prior(
        self, movies: list[MovieFeatures], cold_share: float, rated: dict | None = None
    ) -> np.ndarray:
        n = len(movies)
        logpop = np.array(
            [math.log1p(m.popularity) if m.popularity is not None else -1.0 for m in movies]
        )
        order = np.argsort(-logpop, kind="stable")
        rank = np.empty(n, dtype=int)
        rank[order] = np.arange(n)
        h = min(n, self.head_rank)
        in_head = rank < h
        pct = np.where(in_head, 1.0 - rank / max(1, h - 1), 0.0)
        if self.pop_mode == "pct":
            pop = pct - 0.5
        elif self.pop_mode == "sqrt":
            pop = np.sqrt(pct) - 2 / 3
        elif self.pop_mode == "log":  # log popularity ratio to the pool's most popular film
            pop = np.where(in_head, logpop - logpop[in_head].max(), 0.0) if h else pct
        elif self.pop_mode == "flattop":  # linear up to pct t, flat above (catalog-prior shape)
            pop = np.minimum(pct, self.t) / self.t
        elif self.pop_mode == "hinge":  # only the bottom of the head is pushed down
            pop = -np.maximum(0.0, self.t - pct) / self.t
        elif self.pop_mode == "top":  # only the very top is pushed up
            pop = np.maximum(0.0, pct - self.t) / (1 - self.t)
        elif self.pop_mode == "sq":
            pop = pct**2 - 1 / 3
        else:  # z-score of log popularity inside the head, clipped
            head_lp = logpop[in_head]
            sd = head_lp.std() or 1.0
            pop = np.clip((logpop - head_lp.mean()) / sd, -3, 3) / 4
            pop = np.where(in_head, pop, -0.75)
        votes = np.array([float(m.vote_count or 0) for m in movies])
        avg = np.array([m.vote_average if m.vote_average is not None else np.nan for m in movies])
        has = (votes > 0) & ~np.isnan(avg) & in_head
        mean = float((avg[has] * votes[has]).sum() / votes[has].sum()) if has.any() else 6.0
        avg = np.where(np.isnan(avg), mean, avg)
        shrunk = (votes * avg + self.prior_votes * mean) / (votes + self.prior_votes)
        q = self.q * (1 + self.q_cold_boost * cold_share)
        p = self.p * self._pop_scale(logpop[in_head], rated or {})
        prior = q * (shrunk - mean) + p * pop - self.junk * (~in_head)
        if self.cat:
            prior = prior + self.cat * np.array([m.prior for m in movies])
        return prior

    def score(self, vector, taste, movies):
        s = super().score(vector, taste, movies)
        if not movies:
            return s
        signals = self._signals(vector, taste)
        cold_share = 1 / (1 + signals / self.half)
        alpha = self.warm + (self.cold - self.warm) * cold_share
        raw = self.pool_prior(movies, cold_share, taste.ratings)
        prior = alpha * (raw - np.median(raw))
        rejected = taste.disliked | taste.dismissed
        mask = np.array([m.movie_id in rejected for m in movies]) if rejected else None
        predicted = s.predicted - s.prior + prior
        if mask is not None:
            predicted = np.where(mask, cr.REJECTED_SCORE, predicted)
        s.predicted, s.prior = predicted, prior
        return s


def make_with(**kw):
    def make():
        return PoolPriorRecommender(InMemoryCatalog([]), **kw)

    return make


make = make_with()


def make_env():
    """Screening only: kwargs from the PR_KW environment variable (JSON)."""
    import json
    import os

    return PoolPriorRecommender(InMemoryCatalog([]), **json.loads(os.environ.get("PR_KW", "{}")))


# Named variants (--recommender eval.experiments.pr_poolprior:<name>)
make_p6 = make_with(p=6.0)
make_p6_h40 = make_with(p=6.0, half=40.0)
make_p6_c4 = make_with(p=6.0, cold=4.0)
make_p8_share = make_with(p=8.0, share_lo=-1.0, share_hi=1.3)
make_p10_share = make_with(p=10.0, share_lo=-1.0, share_hi=1.3)
make_p6_share = make_with(p=6.0, share_lo=-1.0, share_hi=1.3)
make_p8 = make_with(p=8.0)
