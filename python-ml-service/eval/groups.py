"""Synthetic groups of MovieLens users.

Three construction methods, each deterministic given (seed, method):

* `random`: members drawn uniformly without replacement.
* `similar`: a random seed member, then greedily add the remaining user with the HIGHEST
  mean cosine similarity (of 1-10 train-rating vectors, unrated = 0) to current members.
* `dissimilar`: same, but the LOWEST mean cosine similarity.

Group sizes are uniform in [min_size, max_size].
"""

from __future__ import annotations

import random
from dataclasses import dataclass

import numpy as np

from eval.data import RatingEvent

METHODS = ("random", "similar", "dissimilar")


@dataclass(frozen=True)
class Group:
    group_id: str
    method: str
    members: tuple[int, ...]  # MovieLens user ids


def cosine_matrix(users: list[int], train: dict[int, list[RatingEvent]]) -> np.ndarray:
    """users x users cosine similarity of train-rating vectors (row order = `users`)."""
    items = sorted({r.movie_id for u in users for r in train[u]})
    col = {m: j for j, m in enumerate(items)}
    mat = np.zeros((len(users), len(items)), dtype=np.float64)
    for i, u in enumerate(users):
        for r in train[u]:
            mat[i, col[r.movie_id]] = r.score
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    unit = mat / norms
    return unit @ unit.T


def _greedy(sim: np.ndarray, size: int, rng: random.Random, most_similar: bool) -> list[int]:
    n = sim.shape[0]
    chosen = [rng.randrange(n)]
    while len(chosen) < size:
        mean_sim = sim[:, chosen].mean(axis=1)
        mean_sim[chosen] = -np.inf if most_similar else np.inf
        chosen.append(int(np.argmax(mean_sim) if most_similar else np.argmin(mean_sim)))
    return chosen


def make_groups(
    users: list[int],
    sim: np.ndarray,
    n_groups: int,
    method: str,
    seed: int,
    min_size: int = 2,
    max_size: int = 6,
) -> list[Group]:
    if method not in METHODS:
        raise ValueError(f"unknown method {method!r}; expected one of {METHODS}")
    if len(users) < min_size:
        return []
    rng = random.Random(f"{seed}:{method}")
    groups = []
    for g in range(n_groups):
        size = rng.randint(min_size, min(max_size, len(users)))
        if method == "random":
            idx = rng.sample(range(len(users)), size)
        else:
            idx = _greedy(sim, size, rng, most_similar=method == "similar")
        groups.append(Group(f"{method}-{g}", method, tuple(users[i] for i in idx)))
    return groups


def mean_pairwise_similarity(group: Group, users: list[int], sim: np.ndarray) -> float:
    index = {u: i for i, u in enumerate(users)}
    idx = [index[u] for u in group.members]
    pairs = [sim[a, b] for i, a in enumerate(idx) for b in idx[i + 1 :]]
    return float(np.mean(pairs)) if pairs else 1.0
