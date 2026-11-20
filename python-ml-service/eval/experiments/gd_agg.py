"""Group-aggregation experiments (gd-* runs): decompose scores, add a merged-profile member.

A member's predicted score is personal_i + prior_i (prior_i = alpha_i * centred quality/popularity
prior; personal_i = content + item-kNN taste). Each strategy aggregates

    rank_on_i = (1 - lam) * personal_i + lam * merged + G * gprior

* gprior - weighted mean of the members' prior_i, added ONCE (not per member: the shared prior
  otherwise makes every member's scores ~identical and least misery just picks the smallest alpha).
* merged - the personal part of a pseudo-member built from the whole group (`merge_tastes`:
  ratings centred per member and averaged, loved interleaved, disliked/dismissed unioned;
  `merge_vectors`: mean interpretable vector). Pooling ratings gives item-kNN more support, so the
  group's collaborative signal is stronger than any one member's ("profile aggregation").
* sim_rcos / sim_params - members whose raw rating vectors are alike (mean pairwise cosine
  >= sim_rcos)
  use sim_params = (lam, G) instead (default: lam 1, G 1 = fully merged profile).
* per_strategy - optional {strategy: (lam, G)} overrides.

member_scores / group_score / explanations always report each member's own full predicted score.
Results (leaderboard-v2, full): make_mad13 score 1.040 (ml_group +6.3%, ml_similar +6.8%).

    --recommender eval.experiments.gd_agg:make_mad13
"""

from __future__ import annotations

import numpy as np

from app.models.contract import GroupRecommendationRequest, GroupRecommendationResponse
from app.services import content_recommender as cr
from app.services import group_strategies as gs
from app.services.catalog import InMemoryCatalog


def _agreement(personal: np.ndarray) -> float:
    if personal.shape[0] < 2 or personal.shape[1] < 3:
        return 1.0
    c = np.nan_to_num(np.corrcoef(personal), nan=0.0)
    return float(c[~np.eye(personal.shape[0], dtype=bool)].mean())


def merge_tastes(tastes: list[cr.UserTaste]) -> cr.UserTaste:
    """One pseudo-member for the whole group: ratings centred per member then averaged (plus the
    members' average mean), loved interleaved, disliked / dismissed unioned."""
    sums: dict[int, list[float]] = {}
    means = []
    for t in tastes:
        if not t.ratings:
            continue
        mu = float(np.mean(list(t.ratings.values())))
        means.append(mu)
        for m, r in t.ratings.items():
            sums.setdefault(m, []).append(r - mu)
    base = float(np.mean(means)) if means else 0.0
    ratings = {m: base + float(np.mean(v)) for m, v in sums.items()}
    loved: list[int] = []
    seen: set[int] = set()
    for i in range(max((len(t.loved) for t in tastes), default=0)):
        for t in tastes:
            if i < len(t.loved) and t.loved[i] not in seen:
                seen.add(t.loved[i])
                loved.append(t.loved[i])
    return cr.UserTaste(
        loved=loved,
        disliked=set().union(*(t.disliked for t in tastes)) - seen,
        dismissed=set().union(*(t.dismissed for t in tastes)),
        n_signals=sum(t.n_signals for t in tastes),
        ratings=ratings,
    )


def rating_cosine(ratings: list[dict[int, float]]) -> float:
    """Mean pairwise cosine of members' raw rating vectors (unrated = 0); 0 with < 2 raters."""
    vals = []
    for i in range(len(ratings)):
        for j in range(i + 1, len(ratings)):
            a, b = ratings[i], ratings[j]
            if not a or not b:
                vals.append(0.0)
                continue
            dot = sum(v * b[m] for m, v in a.items() if m in b)
            na = np.sqrt(sum(v * v for v in a.values()))
            nb = np.sqrt(sum(v * v for v in b.values()))
            vals.append(float(dot / (na * nb)))
    return float(np.mean(vals)) if vals else 0.0


def merge_vectors(vectors) -> cr.UserVector | None:
    vs = [cr._interpretable(v) for v in vectors if v is not None]
    if not vs:
        return None
    return cr.UserVector(interpretable=[float(x) for x in np.mean(vs, axis=0)])


class GroupDecomp(cr.ContentRecommender):
    prior_g: float = 1.0
    # G per group from the members' measured agreement (mean pairwise Pearson of personal parts):
    # G = prior_g - adapt * (agree - agree_mid), clipped to [g_min, g_max].
    adapt: float = 0.0
    agree_mid: float = 0.25
    g_min: float = 0.5
    g_max: float = 1.5
    merge_lam: float = 0.0  # weight of the merged-profile pseudo-member in every member's part
    strategies: tuple[str, ...] = ("mean", "least_misery", "fairness", "closeness_weighted")
    per_strategy: dict | None = None  # strategy value -> (merge_lam, prior_g) overrides
    # Members who rate alike (mean pairwise cosine of their rating vectors >= sim_rcos) get
    # sim_params = (merge_lam, prior_g) instead: lean fully on the merged profile.
    sim_rcos: float | None = None
    sim_params: tuple[float, float] = (1.0, 1.0)

    def _group_prior_weight(self, agree: float) -> float:
        if not self.adapt:
            return self.prior_g
        g = self.prior_g - self.adapt * (agree - self.agree_mid)
        return float(np.clip(g, self.g_min, self.g_max))

    def score_members(self, req: GroupRecommendationRequest, movies):
        tastes = [
            self.tastes.taste(m.user_id) if m.vector is not None else cr.EMPTY_TASTE
            for m in req.members
        ]
        member_scored = [
            self.score(m.vector, t, movies) for m, t in zip(req.members, tastes, strict=True)
        ]
        self._last_rcos = rating_cosine([t.ratings for t in tastes])
        merged = None
        if self.merge_lam:
            ms = self.score(
                merge_vectors([m.vector for m in req.members]), merge_tastes(tastes), movies
            )
            merged = ms.predicted - ms.prior
        return member_scored, merged

    def rank_on(self, member_scored, merged, weights, g=None, lam=None):
        scores = np.stack([s.predicted for s in member_scored])
        prior = np.stack([s.prior for s in member_scored])
        personal = scores - prior
        agree = _agreement(gs._active(personal, weights))
        g = self._group_prior_weight(agree) if g is None else g
        lam = self.merge_lam if lam is None else lam
        if merged is not None and lam:
            personal = (1 - lam) * personal + lam * merged[None, :]
        gprior = gs.weighted_mean(prior, weights)
        return scores, personal + g * gprior[None, :], agree, g

    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        if req.strategy.value not in self.strategies:
            return super().recommend_for_group(req)
        movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
        if not movies:
            return GroupRecommendationResponse(results=[])
        member_scored, merged = self.score_members(req, movies)
        weights = np.array([m.weight for m in req.members], dtype=float)
        lam, g = (self.per_strategy or {}).get(req.strategy.value, (None, None))
        if self.sim_rcos is not None and self._last_rcos >= self.sim_rcos:
            lam, g = self.sim_params
        scores, rank_on, agree, g = self.rank_on(member_scored, merged, weights, g=g, lam=lam)
        ranked = gs.aggregate(req.strategy, rank_on, weights, req.closeness, req.k)
        mean_scores = gs.weighted_mean(scores, weights)
        vectors = [
            cr._interpretable(m.vector) if m.vector is not None else None for m in req.members
        ]
        results = []
        for i, _ in ranked:
            results.append(
                cr.GroupRecommendation(
                    movie_id=movies[i].movie_id,
                    group_score=cr._out(mean_scores[i]),
                    member_scores={
                        m.user_id: cr._out(scores[j, i]) for j, m in enumerate(req.members)
                    },
                    explanation=cr._explain_group(
                        movies[i], scores[:, i], weights, vectors, member_scored, i
                    ),
                )
            )
        return GroupRecommendationResponse(results=results)


def _factory(**kw):
    def make():
        rec = GroupDecomp(InMemoryCatalog([]))
        for k, v in kw.items():
            setattr(rec, k, v)
        return rec

    return make


make_decomp = _factory()
make_m1 = _factory(merge_lam=1.0)
make_m07_g13 = _factory(merge_lam=0.7, prior_g=1.3)
make_m07_g115 = _factory(merge_lam=0.7, prior_g=1.15)
make_mad = _factory(merge_lam=0.7, prior_g=1.15, sim_rcos=0.3, sim_params=(1.0, 1.0))
make_mps = _factory(
    merge_lam=1.0,
    per_strategy={
        "mean": (1.0, 1.0),
        "least_misery": (0.5, 1.3),
        "fairness": (0.7, 1.0),
        "closeness_weighted": (0.7, 1.3),
    },
)
make_g07 = _factory(prior_g=0.7)
make_g05 = _factory(prior_g=0.5)
make_mad13 = _factory(merge_lam=0.7, prior_g=1.3, sim_rcos=0.3, sim_params=(1.0, 1.0))
# Combined best candidate: like mad13, but least misery keeps more of each member's own taste
# (lam 0.5) so a clashing group's least-happy member stays protected.
make_best = _factory(
    merge_lam=0.7,
    prior_g=1.3,
    sim_rcos=0.3,
    sim_params=(1.0, 1.0),
    per_strategy={"least_misery": (0.5, 1.3)},
)
