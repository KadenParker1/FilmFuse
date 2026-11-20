"""Keyword-content experiments (kw-*): TF-IDF look-alike similarity and a per-user keyword profile.

KwRec replaces `_similarity` (the signed "like a movie you loved/disliked" boost) with:
  * look-alike: max cosine over TF-IDF token vectors (keywords + genre tokens, IDF from the whole
    catalog, so rare keywords count more) to the user's loved movies (+LOVED) and disliked ones
    (-DISLIKED), instead of Jaccard. Genre tokens are down-weighted by GENRE_TOKEN_W.
  * profile: a keyword profile p = sum over rated movies of (score - user mean) * unit tf-idf
    vector; candidate boost = PROFILE * cos(p, candidate). Uses every rating, not just loved.
Both are part of the content score, i.e. blended by (1 - beta) against item-kNN CF.

Factories (--recommender eval.experiments.kw_tfidf:<name>) are generated from VARIANTS below.
"""

from __future__ import annotations

import math
from collections import Counter

import numpy as np

from app.services import content_recommender as cr
from app.services import features
from app.services.catalog import InMemoryCatalog, MovieFeatures, UserTaste


def _all_movies(catalog) -> list[MovieFeatures]:
    inner = catalog._ensure_loaded() if hasattr(catalog, "_ensure_loaded") else catalog
    return list(getattr(inner, "_movies", {}).values())


class KwRec(cr.ContentRecommender):
    def __init__(
        self,
        catalog,
        loved: float = 1.0,
        disliked: float = 2.0,
        profile: float = 0.0,
        genre_token_w: float = 0.5,
        no_kw_factor: float = 0.4,
        min_df: int = 2,
        sublinear_idf: bool = False,
    ) -> None:
        super().__init__(catalog)
        self.w_loved, self.w_disliked, self.w_profile = loved, disliked, profile
        self.genre_token_w, self.no_kw_factor = genre_token_w, no_kw_factor
        self.min_df, self.sublinear_idf = min_df, sublinear_idf
        self._idf: dict[str, float] | None = None
        self._default_idf = 1.0
        self._vec_cache: dict[int, dict[str, float]] = {}

    # ---- tf-idf ------------------------------------------------------------------------------
    def _ensure_idf(self) -> None:
        if self._idf is not None:
            return
        movies = _all_movies(self.catalog)
        df: Counter[str] = Counter()
        for m in movies:
            df.update(m.tokens)
        n = max(len(movies), 1)
        self._idf = {}
        for t, c in df.items():
            v = math.log((n + 1) / (c + 1)) + 1.0
            if self.sublinear_idf:
                v = math.sqrt(v)
            self._idf[t] = v
        # unseen / singleton tokens: treat like a token seen min_df times
        v = math.log((n + 1) / (self.min_df + 1)) + 1.0
        self._default_idf = math.sqrt(v) if self.sublinear_idf else v

    def _vec(self, m: MovieFeatures) -> dict[str, float]:
        """Unit-length tf-idf vector of a movie's tokens."""
        got = self._vec_cache.get(m.movie_id)
        if got is not None:
            return got
        self._ensure_idf()
        assert self._idf is not None
        w = {}
        for t in m.tokens:
            v = self._idf.get(t, self._default_idf)
            if t.startswith("g:"):
                v *= self.genre_token_w
            w[t] = v
        norm = math.sqrt(sum(v * v for v in w.values())) or 1.0
        out = {t: v / norm for t, v in w.items()}
        self._vec_cache[m.movie_id] = out
        return out

    # ---- similarity ---------------------------------------------------------------------------
    def _similarity(
        self, taste: UserTaste, movies: list[MovieFeatures]
    ) -> tuple[np.ndarray, list[int | None]]:
        n = len(movies)
        boost = np.zeros(n)
        best: list[int | None] = [None] * n
        if n == 0:
            return boost, best
        factor = np.array([1.0 if m.keywords else self.no_kw_factor for m in movies])
        need = set(taste.loved) | set(taste.disliked)
        if self.w_profile:
            need |= set(taste.ratings)
        if not need:
            return boost, best
        refs = self.catalog.get_many(need)
        cvecs = [self._vec(m) for m in movies]

        def matrix(ref_movies: list[MovieFeatures]):
            vocab: dict[str, int] = {}
            rows = [self._vec(m) for m in ref_movies]
            for r in rows:
                for t in r:
                    vocab.setdefault(t, len(vocab))
            R = np.zeros((len(rows), len(vocab)))
            for i, r in enumerate(rows):
                for t, v in r.items():
                    R[i, vocab[t]] = v
            C = np.zeros((n, len(vocab)))
            for i, c in enumerate(cvecs):
                for t, v in c.items():
                    j = vocab.get(t)
                    if j is not None:
                        C[i, j] = v
            return C @ R.T  # cosines (n, refs)

        loved = [refs[m] for m in taste.loved if m in refs]
        disliked = [refs[m] for m in taste.disliked if m in refs]
        if loved and self.w_loved:
            sim = matrix(loved) * factor[:, None]
            position = {m.movie_id: i for i, m in enumerate(movies)}
            for r, ref in enumerate(loved):
                i = position.get(ref.movie_id)
                if i is not None:
                    sim[i, r] = 0.0
            arg = sim.argmax(axis=1)
            top = sim[np.arange(n), arg]
            boost += self.w_loved * top
            best = [loved[a].movie_id if top[i] > 0 else None for i, a in enumerate(arg)]
        if disliked and self.w_disliked:
            sim = matrix(disliked) * factor[:, None]
            boost -= self.w_disliked * sim.max(axis=1)
        if self.w_profile and taste.ratings:
            rated = [(refs[m], s) for m, s in taste.ratings.items() if m in refs]
            if len(rated) >= 2:
                mean = sum(s for _, s in rated) / len(rated)
                p: dict[str, float] = {}
                for m, s in rated:
                    d = s - mean
                    if d == 0:
                        continue
                    for t, v in self._vec(m).items():
                        p[t] = p.get(t, 0.0) + d * v
                norm = math.sqrt(sum(v * v for v in p.values()))
                if norm > 0:
                    cos = np.array([sum(v * p.get(t, 0.0) for t, v in c.items()) for c in cvecs])
                    boost += self.w_profile * factor * cos / norm
        return boost, best


class JaccardProfileRec(KwRec):
    """Baseline Jaccard look-alike (unchanged) plus only the tf-idf keyword profile term."""

    def _similarity(self, taste, movies):
        boost, best = cr.ContentRecommender._similarity(self, taste, movies)
        saved = (self.w_loved, self.w_disliked)
        self.w_loved = self.w_disliked = 0.0
        try:
            extra, _ = KwRec._similarity(self, taste, movies)
        finally:
            self.w_loved, self.w_disliked = saved
        return boost + extra, best


VARIANTS = {
    # tf-idf look-alike at roughly Jaccard's scale (cosines run ~2-3x Jaccard)
    "tfidf": dict(loved=0.5, disliked=1.0),
    "tfidf_hi": dict(loved=1.0, disliked=2.0),
    "tfidf_prof": dict(loved=0.5, disliked=1.0, profile=2.0),
    "prof_only": dict(loved=0.0, disliked=0.0, profile=3.0),
}


def _factory(cls, kw):
    def make():
        return cls(InMemoryCatalog([]), **kw)

    return make


for _name, _kw in VARIANTS.items():
    globals()[f"make_{_name}"] = _factory(KwRec, _kw)

make_jac_prof2 = _factory(JaccardProfileRec, dict(profile=2.0))
make_jac_prof4 = _factory(JaccardProfileRec, dict(profile=4.0))

_ = features  # mood-rule variants patch features.MOOD_RULES before the catalog loads

# diagnostic sweeps (content-only analysis, scratchpad cold.py)
make_prof1 = _factory(KwRec, dict(loved=0.0, disliked=0.0, profile=1.0))
make_prof6 = _factory(KwRec, dict(loved=0.0, disliked=0.0, profile=6.0))
make_tfidf_lo = _factory(KwRec, dict(loved=0.25, disliked=0.5))
make_prof10 = _factory(KwRec, dict(loved=0.0, disliked=0.0, profile=10.0))
make_prof15 = _factory(KwRec, dict(loved=0.0, disliked=0.0, profile=15.0))


class ShrinkAffinityRec(KwRec):
    """KwRec whose genre/mood affinity is shrunk towards neutral: the user's interpretable dims are
    pulled to 5 + AFF_SCALE * (dim - 5) before scoring (affinity is linear in them)."""

    def __init__(self, catalog, aff_scale: float = 0.5, **kw) -> None:
        super().__init__(catalog, **kw)
        self.aff_scale = aff_scale

    def score(self, vector, taste, movies):
        if vector is not None:
            from app.models.contract import UserVector

            vals = [5.0 + self.aff_scale * (float(v) - 5.0) for v in vector.interpretable]
            vector = UserVector(interpretable=vals, latent=vector.latent)
        return super().score(vector, taste, movies)


make_aff05_prof10 = _factory(
    ShrinkAffinityRec, dict(aff_scale=0.5, loved=0.0, disliked=0.0, profile=10.0)
)
make_aff025_prof10 = _factory(
    ShrinkAffinityRec, dict(aff_scale=0.25, loved=0.0, disliked=0.0, profile=10.0)
)
make_aff05_prof6 = _factory(
    ShrinkAffinityRec, dict(aff_scale=0.5, loved=0.0, disliked=0.0, profile=6.0)
)
make_tfidf_prof10 = _factory(KwRec, dict(loved=0.5, disliked=1.0, profile=10.0))
make_prof20 = _factory(KwRec, dict(loved=0.0, disliked=0.0, profile=20.0))
