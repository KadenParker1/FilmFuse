"""Prototype: TMDB's own film-to-film recommendations as a signal (experiment only).

TMDB computes "people who liked X also liked Y" lists from its users (GET /movie/{id}/
recommendations). For each candidate we count how many of the user's loved movies list it:

    score += W * hits / (hits + SHRINK)

LICENCE: TMDB's API terms require a commercial licence for commercial use. This file is for
offline evaluation; shipping the signal is the owner's decision after checking the terms.

Needs TMDB_ACCESS_TOKEN (the owner's v4 read token, the same variable core-api uses) in the
environment; without it the signal is off (scores equal hybrid-v6). Responses are cached in
eval/data/tmdb_recommendations.json (gitignored) so repeated runs don't re-fetch. Rate-limited.

    TMDB_ACCESS_TOKEN=... ML_DATABASE_URL=... python -m eval.experiment run --name tmdb-sim-w1 \\
        --recommender eval.experiments.tmdb_similar:make_w1
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np

from app.services.catalog import InMemoryCatalog, MovieFeatures, UserTaste
from app.services.content_recommender import REJECTED_SCORE, ContentRecommender, Scored

log = logging.getLogger(__name__)

CACHE = Path(__file__).resolve().parents[1] / "data" / "tmdb_recommendations.json"
API = "https://api.themoviedb.org/3/movie/{id}/recommendations?page=1"
MAX_PER_SECOND = 30
MAX_LOVED = 20  # look up at most this many of the user's loved movies


class TmdbRecommendations:
    def __init__(self) -> None:
        self.token = os.environ.get("TMDB_ACCESS_TOKEN") or None
        self.cache: dict[str, list[int]] = json.loads(CACHE.read_text()) if CACHE.exists() else {}
        self._lock = threading.Lock()
        self._last = 0.0
        self._dirty = 0
        if not self.token:
            log.warning("TMDB_ACCESS_TOKEN not set: TMDB similar signal is off")

    def get(self, movie_id: int) -> list[int]:
        key = str(movie_id)
        if key in self.cache or not self.token:
            return self.cache.get(key, [])
        with self._lock:
            wait = 1.0 / MAX_PER_SECOND - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
        req = urllib.request.Request(
            API.format(id=movie_id),
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:  # noqa: S310 (fixed https host)
                ids = [int(r["id"]) for r in json.load(resp).get("results", [])]
        except urllib.error.HTTPError as e:
            ids = [] if e.code == 404 else None
        except Exception:
            ids = None
        if ids is None:  # transient failure: don't cache
            return []
        self.cache[key] = ids
        self._dirty += 1
        if self._dirty >= 200:
            self.save()
        return ids

    def save(self) -> None:
        if self._dirty:
            CACHE.parent.mkdir(parents=True, exist_ok=True)
            CACHE.write_text(json.dumps(self.cache))
            self._dirty = 0


class TmdbSimilarRecommender(ContentRecommender):
    W = 1.0
    SHRINK = 1.0

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self.tmdb = TmdbRecommendations()

    def score(
        self, vector, taste: UserTaste, movies: list[MovieFeatures], *args, **kwargs
    ) -> Scored:
        scored = super().score(vector, taste, movies, *args, **kwargs)
        if not movies or not taste.loved or not self.tmdb.token and not self.tmdb.cache:
            return scored
        hits: dict[int, int] = {}
        for loved in taste.loved[:MAX_LOVED]:
            for m in self.tmdb.get(loved):
                hits[m] = hits.get(m, 0) + 1
        if not hits:
            return scored
        h = np.array([hits.get(m.movie_id, 0) for m in movies], dtype=float)
        boost = self.W * h / (h + self.SHRINK)
        rejected = scored.predicted == REJECTED_SCORE
        scored.predicted = np.where(rejected, scored.predicted, scored.predicted + boost)
        return scored

    def train_on(self, train) -> None:
        super().train_on(train)
        self.tmdb.save()


def _factory(**attrs):
    def make() -> TmdbSimilarRecommender:
        rec = TmdbSimilarRecommender(InMemoryCatalog([]))
        for k, v in attrs.items():
            setattr(rec, k, v)
        return rec

    return make


make_w05 = _factory(W=0.5)
make_w1 = _factory(W=1.0)
make_w2 = _factory(W=2.0)
