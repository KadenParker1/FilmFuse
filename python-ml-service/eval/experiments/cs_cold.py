"""Cold-start experiments (cs-*): better lists for users with only a few ratings.

ColdRec = hybrid-v3 + Jaccard look-alike (unchanged) + the TF-IDF keyword taste profile from
kw_tfidf, with knobs that depend on how many RATINGS the user has (n = len(taste.ratings)):

  * affinity scale s(n): the genre/mood affinity is pulled towards neutral,
        affinity' = 5 + s(n) * (affinity - 5),
    s(0) = 1 (a brand-new user's onboarding answers count in full), then
        s(n) = 1 - AFF_DIP * exp(-max(0, n - 1) / AFF_TAU)   for n >= 1
    i.e. with a handful of ratings the (noisy) vector counts less, and it recovers as ratings
    accumulate (AFF_TAU = 0 disables). Optional AFF_FLOOR_N: n below which dip applies at all.
  * keyword profile weight PROFILE (constant) plus PROFILE_COLD extra * exp(-n / PROFILE_TAU).
  * prior weight alpha: hybrid-v3's curve, optionally overridden by (prior_cold, prior_half).
  * cold CF: beta = BETA_MAX * support / (support + kappa(n)), kappa(n) = KAPPA * (1 - KAPPA_DIP *
    exp(-n / KAPPA_TAU)) - trusts item-kNN co-occurrence sooner for users with few ratings.
  * favourites (loved) get FAV_PROFILE_BOOST extra weight in the keyword profile.

Factories: make_<name> for VARIANTS below; make_env reads JSON kwargs from $CS_KW (screening).
"""

from __future__ import annotations

import json
import math
import os

import numpy as np

from app.models.contract import UserVector
from app.services import content_recommender as cr
from app.services.catalog import InMemoryCatalog
from eval.experiments.kw_tfidf import KwRec


class ColdRec(KwRec):
    def __init__(
        self,
        catalog,
        profile: float = 0.0,
        profile_cold: float = 0.0,
        profile_tau: float = 10.0,
        aff_dip: float = 0.0,
        aff_tau: float = 0.0,
        prior_cold: float | None = None,
        prior_half: float | None = None,
        kappa_dip: float = 0.0,
        kappa_tau: float = 10.0,
        impl_cold: float = 0.0,
        impl_tau: float = 10.0,
        aff_neg_dip: float | None = None,
        fill: float = 0.0,
        fill_moods: bool = True,
        fill_tau: float = 0.0,
        prior_bump: float = 0.0,
        prior_bump_tau: float = 5.0,
        **kw,
    ) -> None:
        super().__init__(catalog, loved=0.0, disliked=0.0, profile=profile, **kw)
        self.base_profile = profile
        self.profile_cold, self.profile_tau = profile_cold, profile_tau
        self.aff_dip, self.aff_tau = aff_dip, aff_tau
        self.prior_cold, self.prior_half = prior_cold, prior_half
        self.kappa_dip, self.kappa_tau = kappa_dip, kappa_tau
        self.impl_cold, self.impl_tau = impl_cold, impl_tau
        self.aff_neg_dip = aff_dip if aff_neg_dip is None else aff_neg_dip
        self.fill, self.fill_moods, self.fill_tau = fill, fill_moods, fill_tau
        self.prior_bump, self.prior_bump_tau = prior_bump, prior_bump_tau

    def fill_unset(self, vector, n: int):
        """Dims the user never touched (exactly neutral 5) take the user's own average over the
        dims they did touch (genres and moods separately): an unknown genre is assumed to be as
        good as this user's typical genre, not "5/10". FILL_TAU > 0: only for few ratings."""
        f = self.fill * (math.exp(-n / self.fill_tau) if self.fill_tau else 1.0)
        if vector is None or not f:
            return vector
        u = np.array([float(v) for v in vector.interpretable])
        ng = len(cr.GENRE_IDS)
        parts = [slice(0, ng)] + ([slice(ng, len(u))] if self.fill_moods else [])
        for sl in parts:
            seg = u[sl]
            unset = np.abs(seg - cr.NEUTRAL_SCORE) < 1e-6
            if unset.any() and (~unset).any():
                seg[unset] = cr.NEUTRAL_SCORE + f * (seg[~unset].mean() - cr.NEUTRAL_SCORE)
        return UserVector(interpretable=list(u), latent=vector.latent)

    # Jaccard look-alike unchanged + keyword profile (weight depends on n)
    def _similarity(self, taste, movies):
        boost, best = cr.ContentRecommender._similarity(self, taste, movies)
        n = len(taste.ratings)
        w = self.base_profile + self.profile_cold * math.exp(-n / self.profile_tau)
        if w:
            self.w_profile = w
            extra, _ = KwRec._similarity(self, taste, movies)
            self.w_profile = self.base_profile
            boost = boost + extra
        return boost, best

    def aff_scale(self, n: int, dip: float | None = None) -> float:
        dip = self.aff_dip if dip is None else dip
        if n == 0 or not self.aff_tau or not dip:
            return 1.0
        return 1.0 - dip * math.exp(-max(0, n - 1) / self.aff_tau)

    def score(self, vector, taste, movies, friend_trust=None, friend_scale=1.0):
        n = len(taste.ratings)
        vector = self.fill_unset(vector, n)
        s, s_neg = self.aff_scale(n), self.aff_scale(n, self.aff_neg_dip)
        if vector is not None and (s != 1.0 or s_neg != 1.0):
            vals = [5.0 + (s if v > 5 else s_neg) * (float(v) - 5.0) for v in vector.interpretable]
            vector = UserVector(interpretable=vals, latent=vector.latent)
        saved = (cr.PRIOR_COLD_WEIGHT, cr.PRIOR_HALF_SIGNALS, cr.KAPPA)
        try:
            if self.prior_cold is not None:
                cr.PRIOR_COLD_WEIGHT = self.prior_cold
            if self.prior_half is not None:
                cr.PRIOR_HALF_SIGNALS = self.prior_half
            if self.prior_bump and n:
                # extra prior weight for users with a few ratings: alpha += B*exp(-(n-1)/T)
                w, c, h = cr.PRIOR_WARM_WEIGHT, cr.PRIOR_COLD_WEIGHT, cr.PRIOR_HALF_SIGNALS
                sig = self._signals(vector, taste)
                target = w + (c - w) / (1 + sig / h)
                target += self.prior_bump * math.exp(-(n - 1) / self.prior_bump_tau)
                cr.PRIOR_COLD_WEIGHT = w + (target - w) * (1 + sig / h)
            if self.kappa_dip and n:
                cr.KAPPA = saved[2] * (1 - self.kappa_dip * math.exp(-n / self.kappa_tau))
            boost = self.impl_cold * math.exp(-n / self.impl_tau) if n else 0.0
            if boost:
                saved_src = self.ratings
                self.ratings = _ImplicitBoost(saved_src, 1.0 + boost)
            try:
                return super().score(vector, taste, movies, friend_trust, friend_scale)
            finally:
                if boost:
                    self.ratings = saved_src
        finally:
            cr.PRIOR_COLD_WEIGHT, cr.PRIOR_HALF_SIGNALS, cr.KAPPA = saved


class _ImplicitBoost:
    """RatingsSource proxy: BlendKNN with its implicit (co-watch) weight multiplied by `mult`."""

    def __init__(self, src, mult: float) -> None:
        self.src, self.mult = src, mult

    def model(self):
        m = self.src.model()
        if m is None or not hasattr(m, "implicit"):
            return m
        return _BoostedModel(m, self.mult)


class _BoostedModel:
    def __init__(self, m, mult: float) -> None:
        self.m, self.mult = m, mult

    def predict(self, user_ratings, item_ids):
        pred, support, top = self.m.explicit.predict(user_ratings, item_ids)
        _, evidence, _ = self.m.implicit.predict(user_ratings, item_ids)
        return pred + self.mult * self.m.implicit_weight * np.log1p(evidence), support, top


def _factory(kw):
    def make():
        return ColdRec(InMemoryCatalog([]), **kw)

    return make


def make_env():
    return ColdRec(InMemoryCatalog([]), **json.loads(os.environ.get("CS_KW", "{}")))


_FB = dict(fill=1.0, prior_bump=1.5, prior_bump_tau=5.0)
VARIANTS: dict[str, dict] = {
    "fill": dict(fill=1.0),
    "fill_bump": _FB,
    "fill_bump_prof": dict(_FB, profile_cold=6.0, profile_tau=10.0),
    "fill_bump_kappa": dict(_FB, kappa_dip=0.9, kappa_tau=20.0),
    "fill_dip03": dict(fill=1.0, prior_bump=1.0, prior_bump_tau=5.0, aff_dip=0.3, aff_tau=5.0),
    "mute": dict(aff_dip=1.0, aff_tau=1000.0),
    "mute_cold": dict(fill=1.0, aff_dip=1.0, aff_tau=6.0),
}

for _name, _kw in VARIANTS.items():
    globals()[f"make_{_name}"] = _factory(_kw)

_ = np
