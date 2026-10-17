from datetime import UTC, datetime

import pytest

from app.models.contract import (
    GroupRecommendationRequest,
    Strategy,
    UserRecommendationRequest,
    UserVector,
    UserVectorRequest,
)
from app.services.recommender import PlaceholderRecommender
from eval.builders import (
    build_group_request,
    build_user_recommendation_request,
    build_user_vector_request,
    candidate_pool,
    closeness_matrix,
    derive_favorites,
    derive_genre_prefs,
    user_uuid,
)
from eval.data import Movie, RatingEvent

MOVIES = {
    1: Movie(1, 1, "A", 2000, (28, 35)),
    2: Movie(2, 2, "B", 2000, (28,)),
    3: Movie(3, 3, "C", 2000, (18,)),
}
TRAIN = [
    RatingEvent(7, 1, 9, 100),
    RatingEvent(7, 2, 4, 200),
    RatingEvent(7, 3, 10, 300),
]


def test_user_uuid_deterministic() -> None:
    assert user_uuid(7) == user_uuid(7) != user_uuid(8)


def test_genre_prefs_mean_rating_per_genre() -> None:
    prefs = {p.genre_id: p.score for p in derive_genre_prefs(TRAIN, MOVIES)}
    # Action: (9 + 4) / 2 = 6.5 -> round-half-even 6; Comedy: 9; Drama: 10
    assert prefs == {28: 6, 35: 9, 18: 10}
    strict = {p.genre_id for p in derive_genre_prefs(TRAIN, MOVIES, min_genre_ratings=2)}
    assert strict == {28}


def test_favorites_ordered_by_score_then_recency() -> None:
    favs = derive_favorites(TRAIN + [RatingEvent(7, 4, 10, 50)], favorite_min_score=9)
    assert [(f.movie_id, f.rank) for f in favs] == [(3, 1), (4, 2), (1, 3)]


def test_user_vector_request_is_valid_contract() -> None:
    req = build_user_vector_request(7, TRAIN, MOVIES)
    again = UserVectorRequest.model_validate(req.model_dump(by_alias=True, mode="json"))
    assert again == req
    assert req.user_id == user_uuid(7)
    assert [r.source for r in req.ratings] == ["quick_rate"] * 3
    assert req.ratings[0].created_at == datetime.fromtimestamp(100, tz=UTC)
    assert req.mood_prefs == [] and req.previous_vector is None
    # the placeholder accepts it and copies stated prefs into the vector
    vec = PlaceholderRecommender().build_user_vector(req).vector
    assert vec.interpretable[0] == 6.0  # Action is the first dim


def test_candidate_pool_excludes_seen_and_is_seeded() -> None:
    all_ids = list(range(1, 101))
    a = candidate_pool(all_ids, {1, 2, 3}, "42:user:7")
    assert sorted(a) == list(range(4, 101))
    assert a == candidate_pool(all_ids, {1, 2, 3}, "42:user:7")
    assert a != candidate_pool(all_ids, {1, 2, 3}, "42:user:8")
    assert a != sorted(a)  # shuffled: pool order carries no signal


def test_user_recommendation_request_is_valid_contract() -> None:
    vec = UserVector(interpretable=[5.0] * 27)
    req = build_user_recommendation_request(7, vec, [5, 4], {1, 2}, k=10)
    again = UserRecommendationRequest.model_validate(req.model_dump(by_alias=True, mode="json"))
    assert again == req
    assert req.candidate_movie_ids == [5, 4] and req.exclude_movie_ids == [1, 2]


def test_closeness_matrix() -> None:
    assert closeness_matrix(3, 0.5) == [[1.0, 0.5, 0.5], [0.5, 1.0, 0.5], [0.5, 0.5, 1.0]]


@pytest.mark.parametrize("strategy", list(Strategy))
def test_group_request_is_valid_contract(strategy: Strategy) -> None:
    vec = UserVector(interpretable=[5.0] * 27)
    req = build_group_request([(1, vec), (2, None), (3, vec)], [9, 8], {1}, strategy, k=5)
    again = GroupRecommendationRequest.model_validate(req.model_dump(by_alias=True, mode="json"))
    assert again == req
    assert req.strategy is strategy
    assert len(req.closeness) == 3 and req.members[1].vector is None
    assert [m.user_id for m in req.members] == [user_uuid(1), user_uuid(2), user_uuid(3)]
