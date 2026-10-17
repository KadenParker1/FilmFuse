"""Per-user temporal train/test split.

For each user with at least `min_ratings` ratings, ratings are sorted by
(timestamp, movie_id) and the LAST `test_fraction` of them (at least 1, and at least 1 left
for train) become that user's test set. Ties in timestamp are broken by movie id, so the
split is a pure function of the data (no randomness). Randomness only enters when sampling
evaluation users (`sample_users`), which takes an explicit seed.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass

from eval.data import RatingEvent, group_by_user


@dataclass
class Split:
    train: dict[int, list[RatingEvent]]  # user_id -> ratings in time order
    test: dict[int, list[RatingEvent]]

    @property
    def users(self) -> list[int]:
        return sorted(self.train)


def temporal_split(
    ratings: list[RatingEvent], test_fraction: float = 0.2, min_ratings: int = 20
) -> Split:
    if not 0 < test_fraction < 1:
        raise ValueError("test_fraction must be in (0, 1)")
    train: dict[int, list[RatingEvent]] = {}
    test: dict[int, list[RatingEvent]] = {}
    for user_id, events in group_by_user(ratings).items():
        if len(events) < max(2, min_ratings):
            continue
        events = sorted(events, key=lambda r: (r.timestamp, r.movie_id))
        n_test = min(len(events) - 1, max(1, math.ceil(len(events) * test_fraction)))
        train[user_id] = events[:-n_test]
        test[user_id] = events[-n_test:]
    return Split(train=train, test=test)


def relevant_items(test: list[RatingEvent], threshold: int = 8) -> set[int]:
    return {r.movie_id for r in test if r.score >= threshold}


def eligible_users(split: Split, threshold: int = 8) -> list[int]:
    """Users with at least one relevant test item (others cannot score any hit)."""
    return [u for u in split.users if relevant_items(split.test[u], threshold)]


def sample_users(users: list[int], n: int, seed: int) -> list[int]:
    users = sorted(users)
    if n >= len(users):
        return users
    return sorted(random.Random(seed).sample(users, n))
