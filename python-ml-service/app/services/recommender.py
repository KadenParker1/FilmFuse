"""Recommender interface, the shared vector layout, and the model the service runs.

The live model is `hybrid-v7` (`app/services/content_recommender.py`: content scoring blended
with `app/services/collaborative.py`'s kNN + iALS, plus the friend signal in `social.py`; see
docs/recommender-review.md). To try another
model, implement every method of `Recommender` and return it from `get_recommender()`.
Routes, auth and the request/response contract (`app/models/contract.py`) do not need to change.

`PlaceholderRecommender` is the old deliberately dumb stand-in, kept as a reference
point for tests; nothing serves it.
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


# Reference stand-in only (not served).
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
        movie_ids = _take(_pool(req.candidate_movie_ids), req.exclude_movie_ids, req.k)
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
        movie_ids = _take(_pool(req.candidate_movie_ids), req.exclude_movie_ids, req.k)
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


# PLACEHOLDER — replace with your implementation
def _pool(candidate_movie_ids: list[int] | None) -> list[int]:
    # Use core-api's eligible pool (in given order) when provided, else the hard-coded list.
    return _PLACEHOLDER_CANDIDATES if candidate_movie_ids is None else candidate_movie_ids


def _take(candidates: list[int], exclude: list[int], k: int) -> list[int]:
    excluded = set(exclude)
    return [m for m in candidates if m not in excluded][:k]


@lru_cache
def get_recommender() -> Recommender:
    """hybrid-v7 (content + kNN/iALS blend + friends), reading movies, taste, all ratings and the
    friend graph through ML_DATABASE_URL when it is set. Without it the catalog is empty and the
    model keeps core-api's pool order (still never errors)."""
    from app.core.config import database_url
    from app.services.catalog import InMemoryCatalog, PostgresCatalog, PostgresTasteStore
    from app.services.collaborative import PostgresRatingsSource
    from app.services.content_recommender import CF_MODEL, CF_MODELS, ContentRecommender

    url = database_url()
    if url:
        from app.services.social import PostgresFriendSource

        ratings = PostgresRatingsSource(url, factory=CF_MODELS.get(CF_MODEL))
        return ContentRecommender(
            PostgresCatalog(url), PostgresTasteStore(url), ratings, PostgresFriendSource(url)
        )
    return ContentRecommender(InMemoryCatalog([]))
