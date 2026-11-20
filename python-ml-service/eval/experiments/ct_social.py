"""ct_social: hybrid-v1 + a trust-based social signal from the friend network (experiment only).

    predicted += W * sum_f t_f * (r_fm - mean_f) / (sum_f t_f [f rated m] + SHRINK)

over the user's friends f that rated movie m in TRAIN data (`train_on`), where the trust t_f is
the friend_closeness score (directional, from app.friend_closeness via ML_DATABASE_URL), optionally
multiplied by (1 + measured taste agreement of the pair: cosine of centred TRAIN ratings on
co-rated movies, 0 when fewer than MIN_COMMON) and/or rescaled. Users without friends (all of MovieLens) are
scored exactly as hybrid-v1. Never sees held-out ratings: only what `train_on` receives.

Factories (pass with --recommender eval.experiments.ct_social:<name>):
  make            closeness trust, W=1.0
  make_w2         closeness trust, W=2.0
  make_agree      closeness x measured agreement, W=2.0
  make_agree4     closeness x measured agreement, W=4.0
  make_w3         closeness trust, W=3.0
  make_exposure   ablation: friend-rated movies get +W*trust share regardless of the rating
"""

from __future__ import annotations

import os
from collections import defaultdict
from uuid import UUID

import numpy as np

from app.services.catalog import InMemoryCatalog, MovieFeatures, UserTaste
from app.services.content_recommender import REJECTED_SCORE, ContentRecommender, Scored


def _load_closeness() -> dict[UUID, dict[UUID, float]]:
    url = os.environ.get("ML_DATABASE_URL")
    if not url:
        return {}
    import psycopg

    out: dict[UUID, dict[UUID, float]] = defaultdict(dict)
    with psycopg.connect(url, connect_timeout=5) as conn, conn.cursor() as cur:
        cur.execute("select user_id, friend_id, score from app.friend_closeness")
        for a, b, s in cur.fetchall():
            out[a][b] = float(s)
    return dict(out)


class SocialRecommender(ContentRecommender):
    W = 1.0
    SHRINK = 1.0  # pseudo-trust at 0: one friend's opinion counts partly
    USE_AGREEMENT = False
    MIN_COMMON = 3  # co-rated movies needed to measure agreement
    EXPOSURE_ONLY = (
        False  # ablation: every friend rating counts as +1 (what friends saw, not liked)
    )

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._closeness: dict[UUID, dict[UUID, float]] | None = None
        self._centred: dict[UUID, dict[int, float]] = {}
        self._owner: dict[int, UUID] = {}  # id(UserTaste) -> user
        self._trust: dict[UUID, dict[UUID, float]] = {}

    def train_on(self, train: dict[UUID, list[tuple[int, int, float]]]) -> None:
        super().train_on(train)
        if self._closeness is None:
            self._closeness = _load_closeness()
        self._centred = {}
        for u, events in train.items():
            if events:
                mean = sum(s for _, s, _ in events) / len(events)
                self._centred[u] = {m: s - mean for m, s, _ in events}
        self._owner = {id(self.tastes.taste(u)): u for u in train}
        self._trust = {}
        for u in train:
            friends = self._closeness.get(u, {})
            trust = {}
            for f, c in friends.items():
                if f not in self._centred:
                    continue
                t = c
                if self.USE_AGREEMENT:  # unmeasurable agreement (few co-ratings) = neutral
                    t *= 1.0 + self._agreement(u, f)
                if t > 0:
                    trust[f] = t
            if trust:
                self._trust[u] = trust

    def _agreement(self, u: UUID, f: UUID) -> float:
        a, b = self._centred.get(u, {}), self._centred.get(f, {})
        common = set(a) & set(b)
        if len(common) < self.MIN_COMMON:
            return 0.0
        x = np.array([a[m] for m in common])
        y = np.array([b[m] for m in common])
        den = np.sqrt((x * x).sum() * (y * y).sum())
        return float((x * y).sum() / den) if den else 0.0

    def social(self, user: UUID, movies: list[MovieFeatures]) -> np.ndarray:
        trust = self._trust.get(user)
        n = len(movies)
        if not trust:
            return np.zeros(n)
        num, den = np.zeros(n), np.full(n, self.SHRINK)
        for f, t in trust.items():
            ratings = self._centred[f]
            for i, m in enumerate(movies):
                r = ratings.get(m.movie_id)
                if r is not None:
                    num[i] += t * (1.0 if self.EXPOSURE_ONLY else r)
                    den[i] += t
        return self.W * num / den

    def score(self, vector, taste: UserTaste, movies: list[MovieFeatures]) -> Scored:
        scored = super().score(vector, taste, movies)
        user = self._owner.get(id(taste))
        if user is None or not movies or user not in self._trust:
            return scored
        boost = self.social(user, movies)
        rejected = scored.predicted == REJECTED_SCORE
        scored.predicted = np.where(rejected, scored.predicted, scored.predicted + boost)
        return scored


def _factory(**attrs):
    def make() -> SocialRecommender:
        rec = SocialRecommender(InMemoryCatalog([]))
        for k, v in attrs.items():
            setattr(rec, k, v)
        return rec

    return make


make = _factory(W=1.0)
make_w2 = _factory(W=2.0)
make_agree = _factory(W=2.0, USE_AGREEMENT=True)
make_agree4 = _factory(W=4.0, USE_AGREEMENT=True)
make_w3 = _factory(W=3.0)
make_exposure = _factory(W=1.0, EXPOSURE_ONLY=True)
