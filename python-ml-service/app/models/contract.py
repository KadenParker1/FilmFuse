"""FROZEN contract between core-api and python-ml-service.

JSON uses camelCase; Python uses snake_case (populate_by_name=True accepts both).
Do not change these shapes without coordinating with core-api.
All scores are integers 1-10. User ids are Supabase UUIDs; movie ids are TMDB ints.
"""

from datetime import datetime
from enum import Enum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

Score = Annotated[int, Field(ge=1, le=10)]
K = Annotated[int, Field(ge=1, le=100)]
MovieId = Annotated[int, Field(ge=1)]


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# ---- shared ---------------------------------------------------------------


class UserVector(ContractModel):
    """Hybrid vector: interpretable genre+mood dims (1-10 scale) + optional learned latent."""

    interpretable: list[float]
    latent: list[float] | None = None


class Filters(ContractModel):
    max_runtime: Annotated[int, Field(ge=1)] | None = None
    min_year: int | None = None
    max_content_rating: str | None = None
    streaming_services: list[str] | None = None
    genre_ids: list[int] | None = None


class Strategy(str, Enum):
    MEAN = "mean"
    LEAST_MISERY = "least_misery"
    MOST_PLEASURE = "most_pleasure"
    CLOSENESS_WEIGHTED = "closeness_weighted"
    FAIRNESS = "fairness"


# ---- POST /internal/user-vectors ------------------------------------------


class GenrePref(ContractModel):
    genre_id: int
    score: Score


class MoodPref(ContractModel):
    mood_tag: str
    score: Score


class Favorite(ContractModel):
    movie_id: MovieId
    rank: Annotated[int, Field(ge=1)]


class Rating(ContractModel):
    movie_id: MovieId
    score: Score
    source: Literal["onboarding_swipe", "quick_rate", "post_watch"]
    created_at: datetime


class UserVectorRequest(ContractModel):
    user_id: UUID
    previous_vector: UserVector | None = None
    genre_prefs: list[GenrePref] = []
    mood_prefs: list[MoodPref] = []
    favorites: list[Favorite] = []
    ratings: list[Rating] = []
    unseen: list[MovieId] = []
    dismissals: list[MovieId] = []


class UserVectorResponse(ContractModel):
    vector: UserVector
    dim: int
    model_version: str


# ---- POST /internal/group-recommendations ---------------------------------


class GroupMember(ContractModel):
    user_id: UUID
    vector: UserVector
    weight: Annotated[float, Field(ge=0)] = 1.0


class GroupRecommendationRequest(ContractModel):
    members: Annotated[list[GroupMember], Field(min_length=1)]
    # closeness[i][j] = closeness of members[i] to members[j]; n x n, same order as members.
    closeness: list[list[float]] = []
    filters: Filters = Filters()
    exclude_movie_ids: list[MovieId] = []
    strategy: Strategy = Strategy.MEAN
    k: K = 20

    @model_validator(mode="after")
    def _closeness_is_square(self) -> "GroupRecommendationRequest":
        n = len(self.members)
        if self.closeness and (
            len(self.closeness) != n or any(len(row) != n for row in self.closeness)
        ):
            raise ValueError(f"closeness must be an {n}x{n} matrix (or empty)")
        return self


class GroupRecommendation(ContractModel):
    movie_id: MovieId
    group_score: float
    member_scores: dict[UUID, float]
    explanation: str


class GroupRecommendationResponse(ContractModel):
    results: list[GroupRecommendation]


# ---- POST /internal/user-recommendations ----------------------------------


class UserRecommendationRequest(ContractModel):
    user_id: UUID
    vector: UserVector
    filters: Filters = Filters()
    exclude_movie_ids: list[MovieId] = []
    k: K = 20


class UserRecommendation(ContractModel):
    movie_id: MovieId
    score: float
    explanation: str


class UserRecommendationResponse(ContractModel):
    results: list[UserRecommendation]


# ---- POST /internal/calibration-candidates --------------------------------


class CalibrationCandidatesRequest(ContractModel):
    user_id: UUID
    seen_movie_ids: list[MovieId] = []
    k: K = 20


class CalibrationCandidatesResponse(ContractModel):
    movie_ids: list[MovieId]
