"""Turn a user's TRAIN data into the exact contract models core-api would send.

Derivation (only train ratings are ever used; test ratings never leave the harness):

* `userId`: deterministic UUIDv5 of "movielens-user-<id>" (see `user_uuid`).
* `ratings`: every train rating, `source="quick_rate"`, `createdAt` = the MovieLens
  timestamp (UTC), score already mapped to 1-10.
* `genrePrefs` (stated prefs): for each TMDB genre the user has rated at least
  `min_genre_ratings` train movies in, score = round(mean 1-10 rating of those movies),
  clamped to [1, 10]. Genres below the threshold are omitted (core-api omits unset prefs).
* `favorites`: up to `n_favorites` train movies rated >= `favorite_min_score`, ordered by
  score desc, then most recent first; rank 1 = best.
* `moodPrefs`, `unseen`, `dismissals`, `previousVector`: empty / None (MovieLens has no
  such signals).

Recommendation requests: `candidateMovieIds` = every catalog movie the user (or, for a
group, any member) has NOT rated in train, in a seeded random order so a recommender that
just echoes the pool order scores like random. `excludeMovieIds` = the train items, as
core-api would send already-rated movies. `filters` = default (no constraints).
"""

from __future__ import annotations

import uuid
from collections import defaultdict
from collections.abc import Iterable
from datetime import UTC, datetime

import numpy as np

from app.models.contract import (
    Favorite,
    GenrePref,
    GroupMember,
    GroupRecommendationRequest,
    Rating,
    Strategy,
    UserRecommendationRequest,
    UserVector,
    UserVectorRequest,
)
from eval.data import Movie, RatingEvent

_NAMESPACE = uuid.UUID("6f1c2a52-3c3e-4b55-9d0e-2f0f9f5b7a10")


def user_uuid(movielens_user_id: int) -> uuid.UUID:
    return uuid.uuid5(_NAMESPACE, f"movielens-user-{movielens_user_id}")


def derive_genre_prefs(
    train: Iterable[RatingEvent], movies: dict[int, Movie], min_genre_ratings: int = 1
) -> list[GenrePref]:
    by_genre: dict[int, list[int]] = defaultdict(list)
    for r in train:
        movie = movies.get(r.movie_id)
        if movie is None:
            continue
        for g in movie.genre_ids:
            by_genre[g].append(r.score)
    prefs = []
    for g in sorted(by_genre):
        scores = by_genre[g]
        if len(scores) >= min_genre_ratings:
            score = max(1, min(10, round(sum(scores) / len(scores))))
            prefs.append(GenrePref(genre_id=g, score=score))
    return prefs


def derive_favorites(
    train: Iterable[RatingEvent], n_favorites: int = 5, favorite_min_score: int = 9
) -> list[Favorite]:
    best = sorted(
        (r for r in train if r.score >= favorite_min_score),
        key=lambda r: (-r.score, -r.timestamp, r.movie_id),
    )[:n_favorites]
    return [Favorite(movie_id=r.movie_id, rank=i + 1) for i, r in enumerate(best)]


def build_user_vector_request(
    movielens_user_id: int,
    train: list[RatingEvent],
    movies: dict[int, Movie],
    *,
    min_genre_ratings: int = 1,
    n_favorites: int = 5,
    favorite_min_score: int = 9,
) -> UserVectorRequest:
    return UserVectorRequest(
        user_id=user_uuid(movielens_user_id),
        genre_prefs=derive_genre_prefs(train, movies, min_genre_ratings),
        favorites=derive_favorites(train, n_favorites, favorite_min_score),
        ratings=[
            Rating(
                movie_id=r.movie_id,
                score=r.score,
                source="quick_rate",
                created_at=datetime.fromtimestamp(r.timestamp, tz=UTC),
            )
            for r in train
        ],
    )


def candidate_pool(all_movie_ids: list[int], seen: set[int], seed_key: str) -> list[int]:
    """All movies minus `seen`, shuffled deterministically by `seed_key`."""
    pool = np.array([m for m in all_movie_ids if m not in seen], dtype=np.int64)
    rng = np.random.default_rng(int.from_bytes(uuid.uuid5(_NAMESPACE, seed_key).bytes[:8]))
    rng.shuffle(pool)
    return pool.tolist()


def build_user_recommendation_request(
    movielens_user_id: int,
    vector: UserVector,
    candidates: list[int],
    exclude: Iterable[int],
    k: int,
) -> UserRecommendationRequest:
    return UserRecommendationRequest(
        user_id=user_uuid(movielens_user_id),
        vector=vector,
        exclude_movie_ids=sorted(exclude),
        candidate_movie_ids=candidates,
        k=k,
    )


def closeness_matrix(n: int, default: float = 0.5) -> list[list[float]]:
    """n x n, `default` off-diagonal, 1.0 on the diagonal."""
    return [[1.0 if i == j else default for j in range(n)] for i in range(n)]


def build_group_request(
    members: list[tuple[int, UserVector | None]],
    candidates: list[int],
    exclude: Iterable[int],
    strategy: Strategy,
    k: int,
    closeness_default: float = 0.5,
) -> GroupRecommendationRequest:
    return GroupRecommendationRequest(
        members=[GroupMember(user_id=user_uuid(u), vector=v, weight=1.0) for u, v in members],
        closeness=closeness_matrix(len(members), closeness_default),
        exclude_movie_ids=sorted(exclude),
        candidate_movie_ids=candidates,
        strategy=strategy,
        k=k,
    )
