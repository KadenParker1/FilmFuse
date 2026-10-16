from fastapi.testclient import TestClient

from app.services.recommender import INTERPRETABLE_DIM
from tests.conftest import USER_A, USER_B

VECTOR = {"interpretable": [5.0] * INTERPRETABLE_DIM, "latent": None}

USER_VECTOR_BODY = {
    "userId": USER_A,
    "genrePrefs": [{"genreId": 28, "score": 9}, {"genreId": 27, "score": 2}],
    "moodPrefs": [{"moodTag": "cozy", "score": 8}],
    "favorites": [{"movieId": 603, "rank": 1}],
    "ratings": [
        {"movieId": 550, "score": 7, "source": "quick_rate", "createdAt": "2026-10-01T12:00:00Z"}
    ],
    "unseen": [13],
    "dismissals": [680],
}


def test_health_is_public(client: TestClient) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_internal_requires_key(client: TestClient) -> None:
    r = client.post("/internal/user-vectors", json=USER_VECTOR_BODY)
    assert r.status_code == 401


def test_internal_rejects_wrong_key(client: TestClient) -> None:
    r = client.post(
        "/internal/user-vectors", json=USER_VECTOR_BODY, headers={"X-Internal-Key": "nope"}
    )
    assert r.status_code == 403


def test_user_vectors(client: TestClient, auth: dict[str, str]) -> None:
    r = client.post("/internal/user-vectors", json=USER_VECTOR_BODY, headers=auth)
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"vector", "dim", "modelVersion"}
    assert body["dim"] == INTERPRETABLE_DIM == len(body["vector"]["interpretable"])
    assert body["vector"]["latent"] is None
    assert body["vector"]["interpretable"][0] == 9.0  # Action (28) is the first dim


def test_user_vectors_rejects_score_out_of_range(client: TestClient, auth: dict[str, str]) -> None:
    body = {**USER_VECTOR_BODY, "genrePrefs": [{"genreId": 28, "score": 11}]}
    r = client.post("/internal/user-vectors", json=body, headers=auth)
    assert r.status_code == 422


def test_user_vectors_rejects_bad_rating_score(client: TestClient, auth: dict[str, str]) -> None:
    rating = {**USER_VECTOR_BODY["ratings"][0], "score": 11}
    r = client.post(
        "/internal/user-vectors", json={**USER_VECTOR_BODY, "ratings": [rating]}, headers=auth
    )
    assert r.status_code == 422


def test_group_recommendations(client: TestClient, auth: dict[str, str]) -> None:
    body = {
        "members": [
            {"userId": USER_A, "vector": VECTOR, "weight": 1.0},
            {"userId": USER_B, "vector": VECTOR, "weight": 1.0},
        ],
        "closeness": [[1.0, 0.5], [0.5, 1.0]],
        "filters": {"maxRuntime": 140, "genreIds": [28]},
        "excludeMovieIds": [278],
        "strategy": "least_misery",
        "k": 3,
    }
    r = client.post("/internal/group-recommendations", json=body, headers=auth)
    assert r.status_code == 200
    results = r.json()["results"]
    assert len(results) == 3
    assert all(item["movieId"] != 278 for item in results)
    first = results[0]
    assert set(first) == {"movieId", "groupScore", "memberScores", "explanation"}
    assert set(first["memberScores"]) == {USER_A, USER_B}


def test_group_recommendations_validation(client: TestClient, auth: dict[str, str]) -> None:
    member = {"userId": USER_A, "vector": VECTOR, "weight": 1.0}
    base = {"members": [member], "strategy": "mean", "k": 5}
    bad = [
        {**base, "strategy": "dictator"},
        {**base, "k": 0},
        {**base, "k": 101},
        {**base, "closeness": [[1.0, 0.5]]},
        {**base, "members": []},
    ]
    for body in bad:
        r = client.post("/internal/group-recommendations", json=body, headers=auth)
        assert r.status_code == 422, body


def test_user_recommendations(client: TestClient, auth: dict[str, str]) -> None:
    body = {"userId": USER_A, "vector": VECTOR, "filters": {}, "excludeMovieIds": [550], "k": 5}
    r = client.post("/internal/user-recommendations", json=body, headers=auth)
    assert r.status_code == 200
    results = r.json()["results"]
    assert len(results) == 5
    assert set(results[0]) == {"movieId", "score", "explanation"}
    assert all(item["movieId"] != 550 for item in results)


def test_calibration_candidates(client: TestClient, auth: dict[str, str]) -> None:
    body = {"userId": USER_A, "seenMovieIds": [278, 238], "k": 4}
    r = client.post("/internal/calibration-candidates", json=body, headers=auth)
    assert r.status_code == 200
    ids = r.json()["movieIds"]
    assert len(ids) == 4
    assert not {278, 238} & set(ids)
