"""`leastFavorites` on UserVectorRequest: additive, optional, owner-approved 2026-11-08."""

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.models.contract import Favorite, UserVectorRequest
from app.services.recommender import PlaceholderRecommender
from tests.test_api import USER_VECTOR_BODY

LEAST = [{"movieId": 550, "rank": 2}, {"movieId": 13, "rank": 1}]


def test_an_old_request_without_the_field_still_validates() -> None:
    assert "leastFavorites" not in USER_VECTOR_BODY
    req = UserVectorRequest.model_validate(USER_VECTOR_BODY)
    assert req.least_favorites == []


def test_the_field_defaults_to_an_empty_list() -> None:
    req = UserVectorRequest(user_id=USER_VECTOR_BODY["userId"])
    assert req.least_favorites == []
    # Each instance gets its own list (pydantic copies mutable defaults).
    req.least_favorites.append(Favorite(movie_id=1, rank=1))
    assert UserVectorRequest(user_id=USER_VECTOR_BODY["userId"]).least_favorites == []


def test_camel_case_alias_in_and_out() -> None:
    req = UserVectorRequest.model_validate({**USER_VECTOR_BODY, "leastFavorites": LEAST})
    assert req.least_favorites == [Favorite(movie_id=550, rank=2), Favorite(movie_id=13, rank=1)]
    dumped = req.model_dump(by_alias=True)
    assert dumped["leastFavorites"] == LEAST
    assert "least_favorites" not in dumped
    assert UserVectorRequest.model_validate(dumped) == req


def test_snake_case_name_is_accepted_too() -> None:
    req = UserVectorRequest.model_validate({**USER_VECTOR_BODY, "least_favorites": LEAST})
    assert [f.movie_id for f in req.least_favorites] == [550, 13]


@pytest.mark.parametrize(
    "bad",
    [
        [{"movieId": 0, "rank": 1}],
        [{"movieId": 550, "rank": 0}],
        [{"movieId": 550}],
        [{"rank": 1}],
        {"movieId": 550, "rank": 1},
    ],
)
def test_items_are_validated_like_favorites(bad: object) -> None:
    with pytest.raises(ValidationError):
        UserVectorRequest.model_validate({**USER_VECTOR_BODY, "leastFavorites": bad})


def test_the_endpoint_accepts_requests_with_and_without_it(
    client: TestClient, auth: dict[str, str]
) -> None:
    without = client.post("/internal/user-vectors", json=USER_VECTOR_BODY, headers=auth)
    with_it = client.post(
        "/internal/user-vectors", json={**USER_VECTOR_BODY, "leastFavorites": LEAST}, headers=auth
    )
    assert without.status_code == 200
    assert with_it.status_code == 200
    bad = client.post(
        "/internal/user-vectors",
        json={**USER_VECTOR_BODY, "leastFavorites": [{"movieId": -1, "rank": 1}]},
        headers=auth,
    )
    assert bad.status_code == 422


def test_the_placeholder_ignores_it() -> None:
    rec = PlaceholderRecommender()
    base = UserVectorRequest.model_validate(USER_VECTOR_BODY)
    with_least = UserVectorRequest.model_validate({**USER_VECTOR_BODY, "leastFavorites": LEAST})
    assert rec.build_user_vector(with_least) == rec.build_user_vector(base)
