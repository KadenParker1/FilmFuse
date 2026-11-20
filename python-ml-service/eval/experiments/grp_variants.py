"""Group-aggregation experiment variants (grp-* runs). Eval-only; nothing in app/ is changed.

Each `make_<variant>` patches `content_recommender.aggregate` (the name recommend_for_group
calls) with a wrapper that changes how one or more strategies aggregate, then returns the normal
hybrid-v1 recommender. Scores arriving here are already normalised (gs.NORMALIZE).
Knobs are module constants so they can be set from a factory.

    --recommender eval.experiments.grp_variants:make_awm
"""

from __future__ import annotations

import numpy as np

from app.models.contract import Strategy
from app.services import content_recommender as cr
from app.services import group_strategies as gs

_ORIG = gs.aggregate

# ---- knobs -----------------------------------------------------------------------------------
AWM_THRESHOLD = 3.0  # Average-Without-Misery cut (zscore scale: 1 sd below own average)
SOFT_LAMBDA = 1.0  # soft least misery: mean - lambda * shortfall below threshold
SIM_HI = 0.5  # agreement above which a group counts as "similar"
BLEND = 0.0  # extra weight on min added to mean


def _agreement(scores: np.ndarray, weights: np.ndarray) -> float:
    """Mean pairwise Pearson correlation of active members' predicted scores (0 when <2)."""
    act = gs._active(scores, weights)
    if act.shape[0] < 2 or act.shape[1] < 3:
        return 0.0
    c = np.corrcoef(act)
    c = np.nan_to_num(c, nan=0.0)
    off = c[~np.eye(act.shape[0], dtype=bool)]
    return float(off.mean())


def _awm(scores, weights, k, thr):
    mean = gs.weighted_mean(scores, weights)
    low = gs._active(scores, weights).min(axis=0)
    ok = low >= thr
    # Items with misery fall behind every non-miserable one, ordered by mean.
    val = np.where(ok, mean, mean - 100.0)
    return [(i, float(mean[i])) for i, _ in gs._top(val, k)]


def _soft_lm(scores, weights, k, thr, lam):
    mean = gs.weighted_mean(scores, weights)
    act = gs._active(scores, weights)
    shortfall = np.clip(thr - act, 0.0, None).sum(axis=0)
    val = mean - lam * shortfall
    return gs._top(val, k)


def _patch(fn) -> None:
    cr.aggregate = fn
    gs.aggregate = fn


def _rec():
    from app.services.recommender import get_recommender

    return get_recommender()


# ---- variants --------------------------------------------------------------------------------
def make_awm():
    """mean -> Average-Without-Misery (min >= AWM_THRESHOLD); others unchanged."""

    def agg(strategy, scores, weights, closeness, k):
        if scores.size and strategy == Strategy.MEAN:
            return _awm(scores, weights, k, AWM_THRESHOLD)
        return _ORIG(strategy, scores, weights, closeness, k)

    _patch(agg)
    return _rec()


def make_awm_lm():
    """mean -> AWM; least_misery -> AWM too (mean among non-miserable, i.e. LM as a veto, not
    a maximin); fairness/closeness unchanged."""

    def agg(strategy, scores, weights, closeness, k):
        if scores.size and strategy in (Strategy.MEAN, Strategy.LEAST_MISERY):
            thr = AWM_THRESHOLD if strategy == Strategy.MEAN else gs.MISERY_THRESHOLD
            return _awm(scores, weights, k, thr)
        return _ORIG(strategy, scores, weights, closeness, k)

    _patch(agg)
    return _rec()


def make_softlm():
    """least_misery -> mean - SOFT_LAMBDA * sum of shortfalls below MISERY_THRESHOLD."""

    def agg(strategy, scores, weights, closeness, k):
        if scores.size and strategy == Strategy.LEAST_MISERY:
            return _soft_lm(scores, weights, k, gs.MISERY_THRESHOLD, SOFT_LAMBDA)
        return _ORIG(strategy, scores, weights, closeness, k)

    _patch(agg)
    return _rec()


def _adaptive(strategy, scores, weights, closeness, k):
    """Similarity-aware: when members' predictions agree (mean pairwise r >= SIM_HI) a plain mean
    already serves everyone, so protective strategies relax towards it; otherwise unchanged."""
    if scores.size == 0:
        return []
    r = _agreement(scores, weights)
    if strategy == Strategy.MEAN:
        return (
            _awm(scores, weights, k, AWM_THRESHOLD)
            if r < SIM_HI
            else _ORIG(strategy, scores, weights, closeness, k)
        )
    if strategy == Strategy.LEAST_MISERY:
        if r >= SIM_HI:
            return _awm(scores, weights, k, gs.MISERY_THRESHOLD)
        return _ORIG(strategy, scores, weights, closeness, k)
    if strategy == Strategy.CLOSENESS_WEIGHTED:
        # Use taste agreement alongside friendship closeness: c = max(closeness, agreement).
        c = max(gs.group_closeness(closeness, scores.shape[0]), float(np.clip(r, 0, 1)))
        mean = gs.weighted_mean(scores, weights)
        low = gs._active(scores, weights).min(axis=0)
        return gs._top(c * mean + (1 - c) * low, k)
    return _ORIG(strategy, scores, weights, closeness, k)


def make_adaptive():
    _patch(_adaptive)
    return _rec()


def make_blend():
    """Every non-maximin strategy gets a BLEND*min term (mean + small misery guard);
    least_misery -> soft LM."""

    def agg(strategy, scores, weights, closeness, k):
        if scores.size == 0:
            return []
        mean = gs.weighted_mean(scores, weights)
        low = gs._active(scores, weights).min(axis=0)
        if strategy == Strategy.MEAN:
            return gs._top(mean + BLEND * low, k)
        if strategy == Strategy.LEAST_MISERY:
            return _soft_lm(scores, weights, k, gs.MISERY_THRESHOLD, SOFT_LAMBDA)
        if strategy == Strategy.CLOSENESS_WEIGHTED:
            c = gs.group_closeness(closeness, scores.shape[0])
            return gs._top(mean + (1 - c) * low * 0.5, k)
        return _ORIG(strategy, scores, weights, closeness, k)

    _patch(agg)
    return _rec()


# ---- per-strategy normalisation (run with gs.NORMALIZE "none": scores arrive raw) --------------
MIXNORM = {Strategy.MEAN: "rank"}


def _mixnorm(strategy, scores, weights, closeness, k):
    """Normalise per strategy (MIXNORM, default none), aggregate with the committed code, report
    the members' raw weighted mean as the group score when normalised."""
    if scores.size == 0:
        return []
    mode = MIXNORM.get(strategy, "none")
    if mode == "none":
        return _ORIG(strategy, scores, weights, closeness, k)
    ranked = _ORIG(strategy, gs.normalize(scores, mode), weights, closeness, k)
    raw = gs.weighted_mean(scores, weights)
    return [(i, float(raw[i])) for i, _ in ranked]


def make_borda():
    """mean -> Borda (rank-normalised mean); others raw."""
    MIXNORM.clear()
    MIXNORM[Strategy.MEAN] = "rank"
    _patch(_mixnorm)
    return _rec()


def make_borda_cw():
    """mean and closeness_weighted on per-member ranks; least_misery / fairness raw."""
    MIXNORM.clear()
    MIXNORM.update({Strategy.MEAN: "rank", Strategy.CLOSENESS_WEIGHTED: "rank"})
    _patch(_mixnorm)
    return _rec()
