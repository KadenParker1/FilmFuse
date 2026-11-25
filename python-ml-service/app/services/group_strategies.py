"""Group aggregation for the hybrid model: turn per-member predicted scores into one ranked list.

Input everywhere: `scores` is an (members x movies) array of predicted 1-10 scores, `weights`
the members' contract weights (0 = the member doesn't count). Output: (movie index, group
score) pairs, best first, at most k.

* mean               - weighted mean.
* least_misery       - highest minimum (members with weight > 0); ties by weighted mean. With 3+
                       members the "minimum" is the mean of the least happy half (see _low).
* most_pleasure      - highest maximum; ties by weighted mean.
* closeness_weighted - c*mean + (1-c)*min, where c is the group's average closeness (0-1).
                       Close friends compromise like `mean`; a loose group protects its
                       least-happy member like `least_misery`. Deliberately group-level, not
                       per-member centrality: being central doesn't mean being influential
                       (Delic et al.; see docs/recommender-directions.md).
* fairness           - greedy list in the style of GFAR (Kaya et al. 2020): each pick maximises
                       the weighted gain sum_i w_i * rel_i / (1 + sat_i). rel_i is rank-based, as
                       in GFAR: 1 for member i's own favourite candidate, falling to 0 at their
                       FAIR_DEPTH-th (FAIR_DEPTH = FAIR_DEPTH_PER_K * k). sat_i is what member i
                       already got from earlier picks. Rank, not raw score, so one member's
                       generous scores can't crowd out the others. Movies someone is predicted to
                       dislike (< 3) are used only when nothing else is left.
"""

from __future__ import annotations

import numpy as np

from app.models.contract import Strategy

NEUTRAL = 5.0
MISERY_THRESHOLD = 3.0
SOFT_MISERY_MIN = 3  # groups this big or bigger use the soft minimum (see _low)
SOFT_MISERY_SHARE = 0.5
# Weighted mean as a tiny tie-breaker on top of a min/max aggregate.
_TIE = 1e-3


def _active(scores: np.ndarray, weights: np.ndarray) -> np.ndarray:
    mask = weights > 0
    return scores[mask] if mask.any() else scores


def _low(active: np.ndarray) -> np.ndarray:
    """The 'misery' side of a list: the single unhappiest member for pairs; for SOFT_MISERY_MIN
    members or more, the mean of the least happy SOFT_MISERY_SHARE of them (protects the unhappy
    part of a big group without letting one outlier veto everything; round 5, 2026-11-19)."""
    n = active.shape[0]
    if n < SOFT_MISERY_MIN:
        return active.min(axis=0)
    take = max(1, int(np.ceil(n * SOFT_MISERY_SHARE)))
    return np.sort(active, axis=0)[:take].mean(axis=0)


def weighted_mean(scores: np.ndarray, weights: np.ndarray) -> np.ndarray:
    total = weights.sum()
    if total <= 0:
        return scores.mean(axis=0)
    return (weights[:, None] * scores).sum(axis=0) / total


def group_closeness(closeness: list[list[float]], n: int) -> float:
    """Mean off-diagonal closeness, clamped to 0-1. 0.5 when unknown (no matrix or one member)."""
    if n < 2 or not closeness:
        return 0.5
    m = np.asarray(closeness, dtype=float)
    off = m[~np.eye(n, dtype=bool)]
    return float(np.clip(off.mean(), 0.0, 1.0))


def _top(values: np.ndarray, k: int) -> list[tuple[int, float]]:
    # Stable: equal values keep candidate order (core-api sends the pool most popular first).
    order = np.argsort(-values, kind="stable")[:k]
    return [(int(i), float(values[i])) for i in order]


# How each member's scores are put on a common scale before aggregation. Predicted scores from
# different members aren't directly comparable (a heavy rater's collaborative evidence swings
# further than a light rater's), so least misery would otherwise pick on whoever has the widest
# range. "zscore": per member, 5 + 2 * standard score over the candidates (so the misery
# threshold of 3 means "a standard deviation below their own average"); "none": raw scores.
NORMALIZE = "none"  # zscore / rank hurt least misery in the 2026-11-19 experiments


def normalize(scores: np.ndarray, mode: str | None = None) -> np.ndarray:
    mode = NORMALIZE if mode is None else mode
    if mode == "none" or scores.shape[1] < 2:
        return scores
    if mode == "rank":
        ranks = np.argsort(np.argsort(scores, axis=1, kind="stable"), axis=1)
        return 1.0 + 9.0 * ranks / (scores.shape[1] - 1)
    mean = scores.mean(axis=1, keepdims=True)
    std = scores.std(axis=1, keepdims=True)
    return NEUTRAL + 2.0 * (scores - mean) / np.where(std > 1e-9, std, 1.0)


def aggregate(
    strategy: Strategy,
    scores: np.ndarray,
    weights: np.ndarray,
    closeness: list[list[float]],
    k: int,
) -> list[tuple[int, float]]:
    if scores.size == 0:
        return []
    mean = weighted_mean(scores, weights)
    active = _active(scores, weights)
    if strategy == Strategy.MEAN:
        return _top(mean, k)
    if strategy == Strategy.LEAST_MISERY:
        low = _low(active)
        return [(i, float(low[i])) for i, _ in _top(low + _TIE * mean, k)]
    if strategy == Strategy.MOST_PLEASURE:
        high = active.max(axis=0)
        return [(i, float(high[i])) for i, _ in _top(high + _TIE * mean, k)]
    if strategy == Strategy.CLOSENESS_WEIGHTED:
        c = group_closeness(closeness, scores.shape[0])
        return _top(c * mean + (1 - c) * _low(active), k)
    if strategy == Strategy.FAIRNESS:
        return _fair(scores, weights, mean, k)
    raise ValueError(f"unknown strategy {strategy}")


FAIR_DEPTH_PER_K = 5


def rank_relevance(scores: np.ndarray, depth: int) -> np.ndarray:
    """Per member: 1 for their best candidate, linearly down to 0 at rank `depth` and beyond."""
    n_members, n_movies = scores.shape
    ranks = np.empty_like(scores)
    order = np.argsort(-scores, axis=1, kind="stable")
    ranks[np.arange(n_members)[:, None], order] = np.arange(n_movies)
    return np.clip(1.0 - ranks / max(depth, 1), 0.0, 1.0)


def _fair(
    scores: np.ndarray, weights: np.ndarray, mean: np.ndarray, k: int
) -> list[tuple[int, float]]:
    n_members, n_movies = scores.shape
    w = weights if weights.sum() > 0 else np.ones(n_members)
    rel = rank_relevance(scores, FAIR_DEPTH_PER_K * k)
    misery = _active(scores, weights).min(axis=0) < MISERY_THRESHOLD
    available = np.ones(n_movies, dtype=bool)
    sat = np.zeros(n_members)
    out: list[tuple[int, float]] = []
    for _ in range(min(k, n_movies)):
        gain = (w[:, None] * rel / (1.0 + sat)[:, None]).sum(axis=0) + _TIE * mean
        pickable = available & ~misery
        if not pickable.any():
            pickable = available
        gain = np.where(pickable, gain, -np.inf)
        best = int(np.argmax(gain))
        out.append((best, float(mean[best])))
        available[best] = False
        sat += rel[:, best]
    return out
