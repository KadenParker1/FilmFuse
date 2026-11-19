"""hybrid-v1: FilmFuse's recommender (docs/recommender-directions.md, Phase 0 + item-kNN).

Built by Claude at the owner's request (2026-11-18). content-v1 (genre/mood content scoring) was
Phase 0; hybrid-v1 blends in collaborative filtering (`collaborative.py`, item-kNN) wherever
co-ratings give evidence, and is exactly content-v1 where they don't (new users, unrated
movies). Every number below is a tunable constant; judge changes with `python -m eval.run` and
`tools/personas/sessions.py`.

How it works
------------
1. Movie features (`features.py`): genre one-hot + mood strengths from genres and keywords.
2. User vector (`build_user_vector`): 27 interpretable dims on the 1-10 scale. Each dim starts
   at the stated preference (5 when unset) and is pulled towards the scores the user gave
   movies that have that feature:
       dim = (k*stated + sum w*score*feature) / (k + sum w*feature)
   Ratings weigh 1 (older ones less), favourites count as a 10 and least favourites as a 1
   (weight 3 each), dismissals as a mild 3 (weight 0.5).
3. Predicted score for one user and one movie, 1-10:
       affinity   = 0.7 * weighted mean of the user's dims over the movie's features
                  + 0.3 * the user's lowest dim among the movie's genres
                    (one hated genre sinks a movie; "Family horror" for a horror-hater)
       similarity = +SIM_LOVED * closest match to a movie they loved
                    -SIM_DISLIKED * closest match to one they disliked
                    (Jaccard over genres + TMDB keywords)
       prior      = quality (Bayesian vote average) + popularity, centred on the candidates'
                    median, weighted more for users we know little about (cold start)
       collab     = item-kNN prediction from the user's ratings of similar movies
       taste      = (1-beta)*(affinity + similarity) + beta*collab,
                    beta = BETA_MAX * support / (support + KAPPA) (0 with no co-rating evidence)
       predicted  = taste + alpha*prior (clamped to 1-10 only when returned)
   Movies the user disliked or dismissed score 1.
4. "For you": top predicted scores with a light genre-diversity re-rank (MMR).
5. Groups: per-member predicted scores, then `group_strategies.aggregate`.
6. Explanations name the largest actual contribution to the score, so they are faithful.
"""

from __future__ import annotations

import logging
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING

import numpy as np

from app.models.contract import (
    CalibrationCandidatesRequest,
    CalibrationCandidatesResponse,
    GroupRecommendation,
    GroupRecommendationRequest,
    GroupRecommendationResponse,
    UserRecommendation,
    UserRecommendationRequest,
    UserRecommendationResponse,
    UserVector,
    UserVectorRequest,
    UserVectorResponse,
)
from app.services.catalog import (
    DISLIKED_MAX_SCORE,
    EMPTY_TASTE,
    LOVED_MIN_SCORE,
    MAX_LOVED,
    Catalog,
    InMemoryCatalog,
    InMemoryTasteStore,
    MovieFeatures,
    NullTasteStore,
    TasteStore,
    UserTaste,
)
from app.services.collaborative import EASE, ItemKNN, RatingsSource, StaticSource
from app.services.features import GENRE_INDEX, GENRE_NAMES, dim_name, is_genre_dim
from app.services.group_strategies import aggregate
from app.services.recommender import (
    GENRE_IDS,
    INTERPRETABLE_DIM,
    MOOD_TAGS,
    NEUTRAL_SCORE,
)

if TYPE_CHECKING:  # pragma: no cover
    from eval.harness import EvalTrainingData

log = logging.getLogger(__name__)

MODEL_VERSION = "hybrid-v1"

# ---- user vector ---------------------------------------------------------------------------
STATED_STRENGTH = 3.0  # pseudo-observations behind a stated preference
UNSET_STRENGTH = 1.0  # ... behind the neutral 5 of an unset dim
RATING_WEIGHT = 1.0
RATING_HALF_LIFE_DAYS = 730.0  # a rating two years older than the newest counts half
MIN_RATING_WEIGHT = 0.3
FAVORITE_SCORE, FAVORITE_WEIGHT = 10.0, 3.0
LEAST_FAVORITE_SCORE, LEAST_FAVORITE_WEIGHT = 1.0, 3.0
DISMISSAL_SCORE, DISMISSAL_WEIGHT = 3.0, 0.5

# ---- scoring -------------------------------------------------------------------------------
GENRE_FEATURE_WEIGHT = 1.0
MOOD_FEATURE_WEIGHT = 0.5
MEAN_SHARE = 0.7  # affinity = MEAN_SHARE*mean + (1-MEAN_SHARE)*lowest genre dim
SIM_LOVED = 1.0
SIM_DISLIKED = 2.0
NO_KEYWORD_SIM_FACTOR = 0.4  # genre-only matches are weak evidence
PRIOR_WARM_WEIGHT = 1.0  # alpha for a user we know well
PRIOR_COLD_WEIGHT = 2.0  # alpha for a brand-new user (lean on quality and popularity)
PRIOR_HALF_SIGNALS = 10.0  # alpha halfway between cold and warm at this many signals
REJECTED_SCORE = 1.0
# Collaborative blend: beta = BETA_MAX * support / (support + KAPPA), support = sum of |similarity|
# over the user's rated neighbours of the movie.
CF_MODEL = "knn"  # "knn" (served, explainable), "ease" (offline comparison) or "" (off)
BETA_MAX = 0.9  # tuned on the MovieLens harness, 2026-11-18 (NDCG@10 0.091 vs 0.078 popularity)
KAPPA = 2.0

# ---- lists ---------------------------------------------------------------------------------
DIVERSITY = 0.5  # MMR penalty per unit of genre overlap with an already-picked movie
MMR_POOL = 200  # re-rank only the best N
NO_POOL_LIMIT = 2000  # with no candidate pool, rank the N best-known movies
CALIBRATION_POOL = 1500

# ---- explanations --------------------------------------------------------------------------
LOVE_THRESHOLD = 7.0  # a dim counts as "you love" at or above this
SIM_REASON_MIN = 0.3
CF_REASON_MIN = 0.5
PRIOR_REASON_MIN = 0.4
STRONG_FOR_ALL = 7.0


CF_MODELS = {"knn": ItemKNN, "ease": EASE}


def _dim_weights() -> np.ndarray:
    w = np.full(INTERPRETABLE_DIM, MOOD_FEATURE_WEIGHT)
    w[: len(GENRE_IDS)] = GENRE_FEATURE_WEIGHT
    return w


DIM_WEIGHTS = _dim_weights()


@dataclass
class Scored:
    """One user's predicted scores over a list of movies, with what drove them."""

    movies: list[MovieFeatures]
    predicted: np.ndarray  # (n,) unclamped; clamp with _out() when returning
    affinity: np.ndarray  # (n,) 1-10
    contrib: np.ndarray  # (n, dims): weighted (dim - 5) per present feature
    similarity: np.ndarray  # (n,) signed boost
    best_match: list[int | None]  # loved movie id behind the boost
    prior: np.ndarray  # (n,) alpha * prior
    user: np.ndarray  # (dims,) the user's interpretable values used
    cf_pred: np.ndarray | None = None  # (n,) collaborative prediction, 1-10
    beta: np.ndarray | None = None  # (n,) share of taste taken from CF (0 = content only)
    cf_top: list[int | None] | None = None  # rated movie behind each CF prediction
    ratings: dict[int, float] | None = None  # the user's ratings (for explanations)


def _interpretable(vector: UserVector | None) -> np.ndarray:
    values = list(vector.interpretable) if vector is not None else []
    u = np.full(INTERPRETABLE_DIM, NEUTRAL_SCORE)
    n = min(len(values), INTERPRETABLE_DIM)
    u[:n] = values[:n]
    return np.clip(u, 1.0, 10.0)


def _tokens(m: MovieFeatures) -> set[str]:
    return set(m.keywords) | {f"g:{g}" for g in m.genre_ids}


class ContentRecommender:
    model_version = MODEL_VERSION

    def __init__(
        self,
        catalog: Catalog,
        tastes: TasteStore | None = None,
        ratings: RatingsSource | None = None,
    ) -> None:
        self.catalog = catalog
        self.tastes = tastes or NullTasteStore()
        self.ratings = ratings or StaticSource(None)

    # ---- offline evaluation hook ----------------------------------------------------------
    def prepare_for_eval(self, data: EvalTrainingData) -> None:
        """Offline there's no database: build the catalog and taste store from TRAIN data only.
        MovieLens has no TMDB votes or popularity, so they come from train ratings."""
        counts: Counter[int] = Counter()
        sums: dict[int, int] = defaultdict(int)
        for events in data.train.values():
            for r in events:
                counts[r.movie_id] += 1
                sums[r.movie_id] += r.score
        movies = [
            MovieFeatures(
                movie_id=m.tmdb_id,
                title=m.title,
                year=m.year,
                genre_ids=tuple(m.genre_ids),
                keywords=frozenset(),
                popularity=float(counts[m.tmdb_id]),
                vote_average=(sums[m.tmdb_id] / counts[m.tmdb_id]) if counts[m.tmdb_id] else None,
                vote_count=counts[m.tmdb_id],
            )
            for m in data.movies.values()
        ]
        self.catalog = InMemoryCatalog(movies, prior_votes=10)
        store = InMemoryTasteStore()
        for user_id, events in data.train.items():
            ordered = sorted(events, key=lambda r: (-r.score, -r.timestamp))
            store.put(
                user_id,
                UserTaste(
                    loved=[r.movie_id for r in ordered if r.score >= LOVED_MIN_SCORE][:MAX_LOVED],
                    disliked={r.movie_id for r in events if r.score <= DISLIKED_MAX_SCORE},
                    n_signals=len(events),
                    ratings={r.movie_id: float(r.score) for r in events},
                ),
            )
        self.tastes = store
        triples = [(u, r.movie_id, r.score) for u, ev in data.train.items() for r in ev]
        self.ratings = StaticSource(CF_MODELS[CF_MODEL](triples) if CF_MODEL else None)

    # ---- user vector ------------------------------------------------------------------------
    def build_user_vector(self, req: UserVectorRequest) -> UserVectorResponse:
        prior = np.full(INTERPRETABLE_DIM, NEUTRAL_SCORE)
        strength = np.full(INTERPRETABLE_DIM, UNSET_STRENGTH)
        for p in req.genre_prefs:
            idx = GENRE_INDEX.get(p.genre_id)
            if idx is not None:
                prior[idx], strength[idx] = p.score, STATED_STRENGTH
        for p in req.mood_prefs:
            if p.mood_tag in MOOD_TAGS:
                idx = len(GENRE_IDS) + MOOD_TAGS.index(p.mood_tag)
                prior[idx], strength[idx] = p.score, STATED_STRENGTH

        evidence: list[tuple[int, float, float]] = []  # movie id, score, weight
        newest = max((r.created_at for r in req.ratings), default=None)
        for r in req.ratings:
            evidence.append(
                (r.movie_id, float(r.score), RATING_WEIGHT * _recency(r.created_at, newest))
            )
        evidence += [(f.movie_id, FAVORITE_SCORE, FAVORITE_WEIGHT) for f in req.favorites]
        evidence += [
            (f.movie_id, LEAST_FAVORITE_SCORE, LEAST_FAVORITE_WEIGHT) for f in req.least_favorites
        ]
        evidence += [(m, DISMISSAL_SCORE, DISMISSAL_WEIGHT) for m in req.dismissals]

        num = prior * strength
        den = strength.copy()
        movies = self.catalog.get_many({m for m, _, _ in evidence})
        for movie_id, score, weight in evidence:
            movie = movies.get(movie_id)
            if movie is None:
                continue
            num += weight * score * movie.vector
            den += weight * movie.vector
        values = np.clip(num / den, 1.0, 10.0)
        return UserVectorResponse(
            vector=UserVector(interpretable=[round(float(v), 3) for v in values], latent=None),
            dim=INTERPRETABLE_DIM,
            model_version=self.model_version,
        )

    # ---- scoring ----------------------------------------------------------------------------
    def _signals(self, vector: UserVector | None, taste: UserTaste) -> float:
        if taste.n_signals:
            return float(taste.n_signals)
        # No taste store data: a vector that moved away from neutral tells us something.
        if vector is None:
            return 0.0
        moved = np.abs(_interpretable(vector) - NEUTRAL_SCORE) > 0.5
        return float(moved.sum())

    def score(
        self, vector: UserVector | None, taste: UserTaste, movies: list[MovieFeatures]
    ) -> Scored:
        n = len(movies)
        u = _interpretable(vector)
        if n == 0:
            empty = np.zeros(0)
            return Scored([], empty, empty, np.zeros((0, INTERPRETABLE_DIM)), empty, [], empty, u)
        feats = np.stack([m.vector for m in movies])  # (n, dims)
        weighted = feats * DIM_WEIGHTS
        wsum = weighted.sum(axis=1)
        mean = np.where(wsum > 0, (weighted @ u) / np.where(wsum > 0, wsum, 1), NEUTRAL_SCORE)
        genre_present = feats[:, : len(GENRE_IDS)] > 0
        genre_u = np.where(genre_present, u[: len(GENRE_IDS)], np.inf)
        lowest = genre_u.min(axis=1)
        lowest = np.where(np.isfinite(lowest), lowest, mean)
        affinity = MEAN_SHARE * mean + (1 - MEAN_SHARE) * lowest
        contrib = weighted * (u - NEUTRAL_SCORE)

        similarity, best_match = self._similarity(taste, movies)
        alpha = PRIOR_WARM_WEIGHT + (PRIOR_COLD_WEIGHT - PRIOR_WARM_WEIGHT) / (
            1 + self._signals(vector, taste) / PRIOR_HALF_SIGNALS
        )
        # Centred on the candidates' median: the ranking is unchanged, but scores stay on the
        # 1-10 scale and "popular" means popular *compared with the other options*.
        raw_prior = np.array([m.prior for m in movies])
        prior = alpha * (raw_prior - np.median(raw_prior))
        # Collaborative part: blend towards what people with similar ratings thought, as far as
        # the evidence goes (beta = 0 without it, which is exactly content-only).
        taste_score = affinity + similarity
        cf_pred = beta = cf_top = None
        model = self.ratings.model() if taste.ratings else None
        if model is not None:
            cf_pred, support, cf_top = model.predict(taste.ratings, [m.movie_id for m in movies])
            beta = BETA_MAX * support / (support + KAPPA)
            taste_score = (1 - beta) * taste_score + beta * cf_pred
        # Not clamped here: clamping before ranking would tie every strong movie at 10.
        # Scores are clamped to 1-10 only when returned.
        predicted = taste_score + prior
        rejected = taste.disliked | taste.dismissed
        if rejected:
            mask = np.array([m.movie_id in rejected for m in movies])
            predicted = np.where(mask, REJECTED_SCORE, predicted)
        return Scored(
            movies,
            predicted,
            affinity,
            contrib,
            similarity,
            best_match,
            prior,
            u,
            cf_pred,
            beta,
            cf_top,
            taste.ratings,
        )

    def _similarity(
        self, taste: UserTaste, movies: list[MovieFeatures]
    ) -> tuple[np.ndarray, list[int | None]]:
        """Signed boost from the closest loved and disliked movies (vectorised Jaccard)."""
        n = len(movies)
        boost = np.zeros(n)
        best: list[int | None] = [None] * n
        if not taste.loved and not taste.disliked:
            return boost, best
        refs = self.catalog.get_many(list(taste.loved) + list(taste.disliked))
        loved = [refs[m] for m in taste.loved if m in refs]
        disliked = [refs[m] for m in taste.disliked if m in refs]
        if not loved and not disliked:
            return boost, best
        vocab: dict[str, int] = {}
        for m in loved + disliked:
            for t in _tokens(m):
                vocab.setdefault(t, len(vocab))
        cand = np.zeros((n, len(vocab)))
        cand_size = np.zeros(n)
        factor = np.ones(n)
        for i, m in enumerate(movies):
            toks = _tokens(m)
            cand_size[i] = len(toks)
            if not m.keywords:
                factor[i] = NO_KEYWORD_SIM_FACTOR
            for t in toks:
                j = vocab.get(t)
                if j is not None:
                    cand[i, j] = 1.0

        def jaccard(refs_: list[MovieFeatures]) -> np.ndarray:
            ref = np.zeros((len(refs_), len(vocab)))
            for r, m in enumerate(refs_):
                for t in _tokens(m):
                    ref[r, vocab[t]] = 1.0
            inter = cand @ ref.T
            union = cand_size[:, None] + ref.sum(axis=1)[None, :] - inter
            return np.where(union > 0, inter / np.where(union > 0, union, 1), 0.0)

        if loved:
            sim = jaccard(loved) * factor[:, None]
            for i, m in enumerate(movies):  # a movie isn't "like" itself
                for r, ref in enumerate(loved):
                    if ref.movie_id == m.movie_id:
                        sim[i, r] = 0.0
            arg = sim.argmax(axis=1)
            top = sim[np.arange(n), arg]
            boost += SIM_LOVED * top
            best = [loved[a].movie_id if top[i] > 0 else None for i, a in enumerate(arg)]
        if disliked:
            sim = jaccard(disliked) * factor[:, None]
            boost -= SIM_DISLIKED * sim.max(axis=1)
        return boost, best

    def _movies(self, candidate_ids: list[int] | None, exclude: list[int]) -> list[MovieFeatures]:
        if candidate_ids is None:
            pool = [m.movie_id for m in self.catalog.well_known(NO_POOL_LIMIT)]
        else:
            pool = candidate_ids
        excluded = set(exclude)
        ordered = list(dict.fromkeys(m for m in pool if m not in excluded))
        known = self.catalog.get_many(ordered)
        # Unknown movies (not in the catalog) still get a neutral, featureless entry so a
        # database outage degrades to "pool order" rather than an empty answer.
        return [
            known.get(m) or MovieFeatures(m, "", None, (), frozenset(), None, None, None)
            for m in ordered
        ]

    # ---- personal ---------------------------------------------------------------------------
    def recommend_for_user(self, req: UserRecommendationRequest) -> UserRecommendationResponse:
        movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
        taste = self.tastes.taste(req.user_id)
        scored = self.score(req.vector, taste, movies)
        picks = _mmr(scored, req.k)
        titles = self._titles(scored, picks)
        return UserRecommendationResponse(
            results=[
                UserRecommendation(
                    movie_id=scored.movies[i].movie_id,
                    score=_out(scored.predicted[i]),
                    explanation=_explain_user(scored, i, titles),
                )
                for i in picks
            ]
        )

    def _titles(self, scored: Scored, picks: list[int]) -> dict[int, str]:
        ids = {scored.best_match[i] for i in picks if scored.best_match[i] is not None}
        if scored.cf_top is not None:
            ids |= {scored.cf_top[i] for i in picks if scored.cf_top[i] is not None}
        return {m: f.title for m, f in self.catalog.get_many(ids).items() if f.title}

    # ---- group ------------------------------------------------------------------------------
    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
        if not movies:
            return GroupRecommendationResponse(results=[])
        member_scored = [
            self.score(
                m.vector,
                self.tastes.taste(m.user_id) if m.vector is not None else EMPTY_TASTE,
                movies,
            )
            for m in req.members
        ]
        scores = np.stack([s.predicted for s in member_scored])
        weights = np.array([m.weight for m in req.members], dtype=float)
        ranked = aggregate(req.strategy, scores, weights, req.closeness, req.k)
        vectors = [_interpretable(m.vector) if m.vector is not None else None for m in req.members]
        results = []
        for i, group_score in ranked:
            results.append(
                GroupRecommendation(
                    movie_id=movies[i].movie_id,
                    group_score=_out(group_score),
                    member_scores={
                        m.user_id: _out(scores[j, i]) for j, m in enumerate(req.members)
                    },
                    explanation=_explain_group(
                        movies[i], scores[:, i], weights, vectors, member_scored, i
                    ),
                )
            )
        return GroupRecommendationResponse(results=results)

    # ---- calibration ------------------------------------------------------------------------
    def calibration_candidates(
        self, req: CalibrationCandidatesRequest
    ) -> CalibrationCandidatesResponse:
        """Well-known movies (people can rate what they've seen), spread across genres, starting
        with the genres the user has rated least so far."""
        seen = set(req.seen_movie_ids)
        known = [m for m in self.catalog.well_known(CALIBRATION_POOL) if m.movie_id not in seen]
        if not known:
            return CalibrationCandidatesResponse(movie_ids=[])
        seen_genres: Counter[int] = Counter()
        for m in self.catalog.get_many(seen).values():
            seen_genres.update(m.genre_ids)
        queues: dict[int, list[MovieFeatures]] = {g: [] for g in GENRE_IDS}
        for m in known:
            if m.genre_ids:
                queues.setdefault(m.genre_ids[0], []).append(m)
        order = sorted(
            (g for g in queues if queues[g]),
            key=lambda g: (seen_genres[g], GENRE_IDS.index(g) if g in GENRE_IDS else 99),
        )
        picked: list[int] = []
        taken: set[int] = set()
        while len(picked) < req.k and any(queues[g] for g in order):
            for g in order:
                while queues[g] and queues[g][0].movie_id in taken:
                    queues[g].pop(0)
                if queues[g]:
                    m = queues[g].pop(0)
                    picked.append(m.movie_id)
                    taken.add(m.movie_id)
                    if len(picked) == req.k:
                        break
        return CalibrationCandidatesResponse(movie_ids=picked)


# ---- helpers ---------------------------------------------------------------------------------


def _out(score: float) -> float:
    """A score as returned to core-api: 1-10, 3 decimals."""
    return round(float(np.clip(score, 1.0, 10.0)), 3)


def _recency(created_at: datetime, newest: datetime | None) -> float:
    if newest is None:
        return 1.0
    age_days = max(0.0, (newest - created_at).total_seconds() / 86400.0)
    return max(MIN_RATING_WEIGHT, 0.5 ** (age_days / RATING_HALF_LIFE_DAYS))


def _genre_overlap(a: MovieFeatures, b: MovieFeatures) -> float:
    ga, gb = set(a.genre_ids), set(b.genre_ids)
    if not ga or not gb:
        return 0.0
    return len(ga & gb) / len(ga | gb)


def _mmr(scored: Scored, k: int) -> list[int]:
    """Greedy re-rank of the best MMR_POOL: score minus DIVERSITY * overlap with earlier picks."""
    order = list(np.argsort(-scored.predicted, kind="stable")[:MMR_POOL])
    picks: list[int] = []
    while order and len(picks) < k:
        best_j, best_val = 0, -np.inf
        for j, i in enumerate(order):
            overlap = max(
                (_genre_overlap(scored.movies[i], scored.movies[p]) for p in picks), default=0.0
            )
            val = scored.predicted[i] - DIVERSITY * overlap
            if val > best_val:
                best_j, best_val = j, val
        picks.append(int(order.pop(best_j)))
    return picks


def _love_dims(scored: Scored, i: int) -> list[int]:
    """Features of movie i the user loves (dim >= LOVE_THRESHOLD), strongest contribution first;
    at most one genre and one mood."""
    c = scored.contrib[i]
    dims = [int(d) for d in np.argsort(-c) if c[d] > 0 and scored.user[d] >= LOVE_THRESHOLD]
    genres = [d for d in dims if is_genre_dim(d)]
    moods = [d for d in dims if not is_genre_dim(d)]
    return (genres[:1] + moods[:1]) if genres and moods else dims[:2]


def _explain_user(scored: Scored, i: int, titles: dict[int, str]) -> str:
    """Name the largest positive part of the score: taste match, a loved look-alike, or the
    quality/popularity prior."""
    parts = {
        "affinity": float(scored.affinity[i] - NEUTRAL_SCORE),
        "similarity": float(scored.similarity[i]),
        "prior": float(scored.prior[i]),
    }
    if scored.beta is not None and scored.cf_pred is not None and scored.cf_top is not None:
        # Collaborative share of the score, as a gain over neutral like the others.
        beta = float(scored.beta[i])
        parts["collaborative"] = beta * float(scored.cf_pred[i] - NEUTRAL_SCORE)
        parts["affinity"] *= 1 - beta
        parts["similarity"] *= 1 - beta
    reason = max(parts, key=lambda key: parts[key])
    if reason == "collaborative" and parts[reason] >= CF_REASON_MIN:
        top = scored.cf_top[i] if scored.cf_top is not None else None
        if top in titles and scored.ratings and top in scored.ratings:
            return f"Because you rated {titles[top]} {scored.ratings[top]:.0f}/10"
    match = scored.best_match[i]
    if reason == "similarity" and parts["similarity"] >= SIM_REASON_MIN and match in titles:
        return f"Like {titles[match]}, which you loved"
    if reason != "prior" and parts["affinity"] > 0:
        dims = _love_dims(scored, i)
        if dims:
            return "Because you love " + " and ".join(dim_name(d) for d in dims)
    if parts["prior"] >= PRIOR_REASON_MIN:
        return "Highly rated and popular"
    genres = [GENRE_NAMES[g] for g in scored.movies[i].genre_ids if g in GENRE_NAMES]
    return f"A {genres[0]} pick worth a try" if genres else "Worth a try"


def _explain_group(
    movie: MovieFeatures,
    scores: np.ndarray,
    weights: np.ndarray,
    vectors: list[np.ndarray | None],
    member_scored: list[Scored],
    i: int,
) -> str:
    """One line for the whole group. Never names or singles out a member."""
    active = weights > 0 if (weights > 0).any() else np.ones_like(weights, dtype=bool)
    s = scores[active]
    if s.min() >= NEUTRAL_SCORE and all(v is not None for v in vectors):
        shared = [
            g
            for g in movie.genre_ids
            if g in GENRE_INDEX
            and all(
                v[GENRE_INDEX[g]] >= LOVE_THRESHOLD
                for v, a in zip(vectors, active, strict=True)
                if a and v is not None
            )
        ]
        if shared:
            return f"Everyone's into {GENRE_NAMES[shared[0]]}"
    if s.min() >= STRONG_FOR_ALL:
        return "Strong pick for everyone"
    if (s >= STRONG_FOR_ALL).mean() >= 0.5 and s.min() >= 4.0:
        return "Big hit for most of you, fine for the rest"
    if s.min() >= NEUTRAL_SCORE:
        return "Nobody's likely to hate it"
    if member_scored and float(np.mean([ms.prior[i] for ms in member_scored])) >= PRIOR_REASON_MIN:
        return "Highly rated and popular"
    return "Worth a try together"
