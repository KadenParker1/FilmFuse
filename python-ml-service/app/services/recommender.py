"""Recommender interface + PLACEHOLDER implementation.

OWNER: this is where your algorithm plugs in.
  1. Write a class that implements every method of `Recommender` (e.g. in
     `app/services/my_recommender.py`). Interest vectors, the group matrix,
     aggregation strategies, ranking, explanations and calibration are yours.
  2. Change the single line in `get_recommender()` below to return it.
Routes, auth and the request/response contract (`app/models/contract.py`)
do not need to change.

`PlaceholderRecommender` is deliberately dumb: no DB access, no maths worth
keeping. It exists only so core-api can be built and tested end-to-end.
"""

from functools import lru_cache
from typing import Protocol

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

# Fixed dimension order of the interpretable vector: one dim per TMDB movie genre,
# then one per mood tag. Changing this order changes the meaning of stored vectors.
GENRE_IDS: list[int] = [
    28, 12, 16, 35, 80, 99, 18, 10751, 14, 36,
    27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37,
]  # fmt: skip
MOOD_TAGS: list[str] = [
    "mind-bending",
    "cozy",
    "laugh-out-loud",
    "edge-of-seat",
    "tearjerker",
    "visually-stunning",
    "dark-gritty",
    "feel-good",
]
INTERPRETABLE_DIM = len(GENRE_IDS) + len(MOOD_TAGS)
NEUTRAL_SCORE = 5.0


class Recommender(Protocol):
    def build_user_vector(self, req: UserVectorRequest) -> UserVectorResponse: ...

    def recommend_for_group(
        self, req: GroupRecommendationRequest
    ) -> GroupRecommendationResponse: ...

    def recommend_for_user(self, req: UserRecommendationRequest) -> UserRecommendationResponse: ...

    def calibration_candidates(
        self, req: CalibrationCandidatesRequest
    ) -> CalibrationCandidatesResponse: ...


# PLACEHOLDER — replace with your implementation
_PLACEHOLDER_CANDIDATES: list[int] = [278, 238, 155, 680, 550, 13, 603, 27205, 129, 120]


# PLACEHOLDER — replace with your implementation
class PlaceholderRecommender:
    """Stated prefs copied into a vector; recommendations echo a hard-coded list."""

    model_version = "placeholder-0"

    def build_user_vector(self, req: UserVectorRequest) -> UserVectorResponse:
        # PLACEHOLDER — replace with your implementation
        genre = {p.genre_id: float(p.score) for p in req.genre_prefs}
        mood = {p.mood_tag: float(p.score) for p in req.mood_prefs}
        interpretable = [genre.get(g, NEUTRAL_SCORE) for g in GENRE_IDS] + [
            mood.get(m, NEUTRAL_SCORE) for m in MOOD_TAGS
        ]
        return UserVectorResponse(
            vector=UserVector(interpretable=interpretable, latent=None),
            dim=INTERPRETABLE_DIM,
            model_version=self.model_version,
        )

    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        # PLACEHOLDER — replace with your implementation
        movie_ids = _take(_PLACEHOLDER_CANDIDATES, req.exclude_movie_ids, req.k)
        return GroupRecommendationResponse(
            results=[
                GroupRecommendation(
                    movie_id=m,
                    group_score=NEUTRAL_SCORE,
                    member_scores={member.user_id: NEUTRAL_SCORE for member in req.members},
                    explanation="Placeholder pick",
                )
                for m in movie_ids
            ]
        )

    def recommend_for_user(self, req: UserRecommendationRequest) -> UserRecommendationResponse:
        # PLACEHOLDER — replace with your implementation
        movie_ids = _take(_PLACEHOLDER_CANDIDATES, req.exclude_movie_ids, req.k)
        return UserRecommendationResponse(
            results=[
                UserRecommendation(movie_id=m, score=NEUTRAL_SCORE, explanation="Placeholder pick")
                for m in movie_ids
            ]
        )

    def calibration_candidates(
        self, req: CalibrationCandidatesRequest
    ) -> CalibrationCandidatesResponse:
        # PLACEHOLDER — replace with your implementation
        return CalibrationCandidatesResponse(
            movie_ids=_take(_PLACEHOLDER_CANDIDATES, req.seen_movie_ids, req.k)
        )


def _take(candidates: list[int], exclude: list[int], k: int) -> list[int]:
    excluded = set(exclude)
    return [m for m in candidates if m not in excluded][:k]


@lru_cache
def get_recommender() -> Recommender:
    # OWNER: swap this one line for your implementation.
    return PlaceholderRecommender()
