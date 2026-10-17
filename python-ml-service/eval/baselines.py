"""BASELINES ONLY — trivial reference points for the evaluation report, NOT the FilmFuse
algorithm. They implement the `Recommender` protocol so the harness treats them exactly
like the real recommender. A real recommender should beat both.

* RandomBaseline: uniformly random pick from the candidate pool (seeded per request).
* PopularityBaseline: most-rated movies in the TRAIN split, same list for everyone.
"""

from __future__ import annotations

import random
from collections import Counter

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
from eval.data import RatingEvent

_NEUTRAL = 5.0


class _BaselineBase:
    model_version = "baseline"

    def build_user_vector(self, req: UserVectorRequest) -> UserVectorResponse:
        return UserVectorResponse(
            vector=UserVector(interpretable=[]), dim=0, model_version=self.model_version
        )

    def _rank(self, pool: list[int], exclude: set[int], k: int, key: str) -> list[int]:
        raise NotImplementedError

    def recommend_for_user(self, req: UserRecommendationRequest) -> UserRecommendationResponse:
        ids = self._rank(
            req.candidate_movie_ids or [], set(req.exclude_movie_ids), req.k, str(req.user_id)
        )
        return UserRecommendationResponse(
            results=[
                UserRecommendation(movie_id=m, score=_NEUTRAL, explanation="baseline") for m in ids
            ]
        )

    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        key = ",".join(sorted(str(m.user_id) for m in req.members))
        ids = self._rank(req.candidate_movie_ids or [], set(req.exclude_movie_ids), req.k, key)
        scores = {m.user_id: _NEUTRAL for m in req.members}
        return GroupRecommendationResponse(
            results=[
                GroupRecommendation(
                    movie_id=m, group_score=_NEUTRAL, member_scores=scores, explanation="baseline"
                )
                for m in ids
            ]
        )

    def calibration_candidates(
        self, req: CalibrationCandidatesRequest
    ) -> CalibrationCandidatesResponse:
        return CalibrationCandidatesResponse(movie_ids=[])


class RandomBaseline(_BaselineBase):
    model_version = "baseline-random"

    def __init__(self, seed: int = 0) -> None:
        self.seed = seed

    def _rank(self, pool: list[int], exclude: set[int], k: int, key: str) -> list[int]:
        eligible = [m for m in pool if m not in exclude]
        rng = random.Random(f"{self.seed}:{key}")
        return rng.sample(eligible, min(k, len(eligible)))


class PopularityBaseline(_BaselineBase):
    model_version = "baseline-popularity"

    def __init__(self, train_ratings: list[RatingEvent]) -> None:
        counts = Counter(r.movie_id for r in train_ratings)
        self.order = sorted(counts, key=lambda m: (-counts[m], m))

    def _rank(self, pool: list[int], exclude: set[int], k: int, key: str) -> list[int]:
        allowed = set(pool) - exclude
        out = []
        for m in self.order:
            if m in allowed:
                out.append(m)
                if len(out) == k:
                    break
        return out
