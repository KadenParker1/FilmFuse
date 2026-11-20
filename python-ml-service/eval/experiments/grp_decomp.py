"""Group experiment: aggregate the PERSONAL part of each member's score, add the shared prior once.

Finding behind it: a member's predicted score is taste + alpha_i * prior, and on MovieLens the
prior term has a std of ~14 against ~1 for taste (r ~ 1.0 between members). So `least_misery`
mostly picks the member with the smallest alpha (a weaker popularity weight), `fairness` ranks are
popularity ranks, and normalisation can't separate members. Here each member's score is split
into personal = predicted - prior_i and the group prior = weighted mean of prior_i; the strategy
aggregates `personal_i (+ normalisation) + PRIOR_G * group prior`. member_scores / explanations
stay each member's own full predicted score; group_score is the members' weighted mean.

    --recommender eval.experiments.grp_decomp:make           (PRIOR_G 1.0)
    --recommender eval.experiments.grp_decomp:make_g07 / make_g05 / make_z
"""

from __future__ import annotations

import numpy as np

from app.models.contract import GroupRecommendationRequest, GroupRecommendationResponse
from app.services import content_recommender as cr
from app.services import group_strategies as gs

PRIOR_G = 1.0  # weight of the shared (group-mean) prior added back after aggregating
PERSONAL_NORM = "none"  # "none" | "zscore" | "rank_free": how personal parts are put on one scale
PERSONAL_SCALE = 1.0  # multiplier on personal parts when normalised (zscore gives std 1)


def _rank_scores(scored, weights: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    predicted = np.stack([s.predicted for s in scored])
    prior = np.stack([s.prior for s in scored])
    personal = predicted - prior
    if PERSONAL_NORM == "zscore" and personal.shape[1] > 1:
        mu = personal.mean(axis=1, keepdims=True)
        sd = personal.std(axis=1, keepdims=True)
        personal = PERSONAL_SCALE * (personal - mu) / np.where(sd > 1e-9, sd, 1.0)
    gprior = gs.weighted_mean(prior, weights)
    return predicted, personal + PRIOR_G * gprior[None, :]


def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
    movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
    if not movies:
        return GroupRecommendationResponse(results=[])
    member_scored = [
        self.score(
            m.vector,
            self.tastes.taste(m.user_id) if m.vector is not None else cr.EMPTY_TASTE,
            movies,
        )
        for m in req.members
    ]
    weights = np.array([m.weight for m in req.members], dtype=float)
    scores, rank_on = _rank_scores(member_scored, weights)
    ranked = gs.aggregate(req.strategy, rank_on, weights, req.closeness, req.k)
    mean_scores = gs.weighted_mean(scores, weights)
    vectors = [cr._interpretable(m.vector) if m.vector is not None else None for m in req.members]
    results = []
    for i, _ in ranked:
        results.append(
            cr.GroupRecommendation(
                movie_id=movies[i].movie_id,
                group_score=cr._out(mean_scores[i]),
                member_scores={m.user_id: cr._out(scores[j, i]) for j, m in enumerate(req.members)},
                explanation=cr._explain_group(
                    movies[i], scores[:, i], weights, vectors, member_scored, i
                ),
            )
        )
    return GroupRecommendationResponse(results=results)


def _make(prior_g: float = 1.0, norm: str = "none", scale: float = 1.0):
    global PRIOR_G, PERSONAL_NORM, PERSONAL_SCALE
    PRIOR_G, PERSONAL_NORM, PERSONAL_SCALE = prior_g, norm, scale
    cr.ContentRecommender.recommend_for_group = recommend_for_group
    from app.services.recommender import get_recommender

    return get_recommender()


def make():
    return _make(1.0)


def make_g07():
    return _make(0.7)


def make_g05():
    return _make(0.5)


def make_z():
    """Personal parts z-scored per member (std 1 each), shared prior at full weight."""
    return _make(1.0, "zscore", 1.0)
