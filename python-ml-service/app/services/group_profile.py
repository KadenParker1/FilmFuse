"""Merged group profile: one pseudo-member built from the whole group ("profile aggregation").

Pooling the members' ratings gives the collaborative model far more evidence than any single
member has, so the group's own taste signal is stronger. Found in the round-2 experiments
(2026-11-19, eval/experiments/gd_agg.py): +6% group satisfaction on its own, and with the
explicit+implicit blend +20% overall and +18% for similar-taste groups on MovieLens, without
hurting the least happy member of clashing groups.
"""

from __future__ import annotations

from collections.abc import Iterable

import numpy as np

from app.models.contract import UserVector
from app.services.catalog import UserTaste


def merge_tastes(tastes: list[UserTaste]) -> UserTaste:
    """Ratings centred on each member's own mean, averaged per movie, then shifted back by the
    members' average mean (so a generous and a harsh rater count alike); loved movies
    interleaved member by member; disliked and dismissed movies unioned (minus anything loved)."""
    per_movie: dict[int, list[float]] = {}
    means: list[float] = []
    for t in tastes:
        if not t.ratings:
            continue
        mean = float(np.mean(list(t.ratings.values())))
        means.append(mean)
        for movie, score in t.ratings.items():
            per_movie.setdefault(movie, []).append(score - mean)
    base = float(np.mean(means)) if means else 0.0
    loved: list[int] = []
    seen: set[int] = set()
    for i in range(max((len(t.loved) for t in tastes), default=0)):
        for t in tastes:
            if i < len(t.loved) and t.loved[i] not in seen:
                seen.add(t.loved[i])
                loved.append(t.loved[i])
    return UserTaste(
        loved=loved,
        disliked=set().union(*(t.disliked for t in tastes)) - seen,
        dismissed=set().union(*(t.dismissed for t in tastes)),
        n_signals=sum(t.n_signals for t in tastes),
        ratings={movie: base + float(np.mean(v)) for movie, v in per_movie.items()},
    )


def merge_vectors(vectors: Iterable[np.ndarray | None]) -> UserVector | None:
    """Mean of the members' interpretable vectors (members without one are skipped)."""
    present = [v for v in vectors if v is not None]
    if not present:
        return None
    return UserVector(interpretable=[float(x) for x in np.mean(present, axis=0)])


def rating_cosine(ratings: list[dict[int, float]]) -> float:
    """How alike the members rate: mean pairwise cosine of their raw rating vectors (unrated = 0).
    0 for a pair where either has no ratings, and for fewer than two members."""
    values: list[float] = []
    for i in range(len(ratings)):
        for j in range(i + 1, len(ratings)):
            a, b = ratings[i], ratings[j]
            if not a or not b:
                values.append(0.0)
                continue
            dot = sum(v * b[m] for m, v in a.items() if m in b)
            norm_a = np.sqrt(sum(v * v for v in a.values()))
            norm_b = np.sqrt(sum(v * v for v in b.values()))
            values.append(float(dot / (norm_a * norm_b)) if norm_a and norm_b else 0.0)
    return float(np.mean(values)) if values else 0.0
