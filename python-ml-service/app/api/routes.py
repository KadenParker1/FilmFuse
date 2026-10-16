"""Internal endpoints called by core-api. All require the X-Internal-Key header."""

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.security import require_internal_key
from app.models.contract import (
    CalibrationCandidatesRequest,
    CalibrationCandidatesResponse,
    GroupRecommendationRequest,
    GroupRecommendationResponse,
    UserRecommendationRequest,
    UserRecommendationResponse,
    UserVectorRequest,
    UserVectorResponse,
)
from app.services.recommender import Recommender, get_recommender

router = APIRouter(prefix="/internal", dependencies=[Depends(require_internal_key)])

RecommenderDep = Annotated[Recommender, Depends(get_recommender)]


@router.post("/user-vectors", response_model=UserVectorResponse, response_model_by_alias=True)
def user_vectors(req: UserVectorRequest, recommender: RecommenderDep) -> UserVectorResponse:
    return recommender.build_user_vector(req)


@router.post(
    "/group-recommendations",
    response_model=GroupRecommendationResponse,
    response_model_by_alias=True,
)
def group_recommendations(
    req: GroupRecommendationRequest, recommender: RecommenderDep
) -> GroupRecommendationResponse:
    return recommender.recommend_for_group(req)


@router.post(
    "/user-recommendations",
    response_model=UserRecommendationResponse,
    response_model_by_alias=True,
)
def user_recommendations(
    req: UserRecommendationRequest, recommender: RecommenderDep
) -> UserRecommendationResponse:
    return recommender.recommend_for_user(req)


@router.post(
    "/calibration-candidates",
    response_model=CalibrationCandidatesResponse,
    response_model_by_alias=True,
)
def calibration_candidates(
    req: CalibrationCandidatesRequest, recommender: RecommenderDep
) -> CalibrationCandidatesResponse:
    return recommender.calibration_candidates(req)
