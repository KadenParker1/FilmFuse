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


# ---- phase 3: hard-constraint filters, optional vectors, candidate pool -----

GROUP_BASE = {"members": [{"userId": USER_A, "vector": VECTOR}], "k": 5}
USER_BASE = {"userId": USER_A, "vector": VECTOR, "k": 5}


def test_new_filters_accepted(client: TestClient, auth: dict[str, str]) -> None:
    filters = {
        "maxCertification": "PG-13",
        "contentLimits": {"violence": "moderate", "sexual": "none", "frightening": "mild"},
        "allowUnrated": True,
    }
    for path, base in (
        ("/internal/group-recommendations", GROUP_BASE),
        ("/internal/user-recommendations", USER_BASE),
    ):
        r = client.post(path, json={**base, "filters": filters}, headers=auth)
        assert r.status_code == 200, (path, r.json())


def test_new_filters_rejected(client: TestClient, auth: dict[str, str]) -> None:
    bad_filters = [
        {"maxCertification": "PG13"},
        {"maxCertification": "pg-13"},
        {"maxCertification": "TV-MA"},
        {"contentLimits": {"gore": "mild"}},
        {"contentLimits": {"violence": "extreme"}},
        {"contentLimits": {"Violence": "mild"}},
        {"contentLimits": ["violence"]},
        {"allowUnrated": "maybe"},
    ]
    for filters in bad_filters:
        for path, base in (
            ("/internal/group-recommendations", GROUP_BASE),
            ("/internal/user-recommendations", USER_BASE),
        ):
            r = client.post(path, json={**base, "filters": filters}, headers=auth)
            assert r.status_code == 422, (path, filters)


def test_filters_defaults_and_camel_case_serialization() -> None:
    from app.models.contract import Filters

    assert Filters().model_dump(by_alias=True) == {
        "maxRuntime": None,
        "minYear": None,
        "maxContentRating": None,
        "streamingServices": None,
        "genreIds": None,
        "maxCertification": None,
        "contentLimits": {},
        "allowUnrated": False,
    }
    f = Filters(max_certification="R", content_limits={"drugs": "mild"}, allow_unrated=True)
    dumped = f.model_dump(by_alias=True)
    assert dumped["maxCertification"] == "R"
    assert dumped["contentLimits"] == {"drugs": "mild"}
    assert dumped["allowUnrated"] is True
    assert Filters.model_validate(dumped) == f


def test_candidate_movie_ids_camel_case_serialization() -> None:
    from app.models.contract import GroupRecommendationRequest, UserRecommendationRequest

    g = GroupRecommendationRequest.model_validate({**GROUP_BASE, "candidateMovieIds": [1, 2]})
    assert g.candidate_movie_ids == [1, 2]
    assert g.model_dump(by_alias=True)["candidateMovieIds"] == [1, 2]
    u = UserRecommendationRequest.model_validate(USER_BASE)
    assert u.candidate_movie_ids is None
    assert u.model_dump(by_alias=True)["candidateMovieIds"] is None


def test_group_member_vector_may_be_null(client: TestClient, auth: dict[str, str]) -> None:
    body = {
        "members": [
            {"userId": USER_A, "vector": VECTOR},
            {"userId": USER_B, "vector": None},
        ],
        "k": 2,
    }
    r = client.post("/internal/group-recommendations", json=body, headers=auth)
    assert r.status_code == 200
    for item in r.json()["results"]:
        assert set(item["memberScores"]) == {USER_A, USER_B}
    # Omitted vector is also fine.
    body["members"][1] = {"userId": USER_B}
    r = client.post("/internal/group-recommendations", json=body, headers=auth)
    assert r.status_code == 200
    assert set(r.json()["results"][0]["memberScores"]) == {USER_A, USER_B}


def test_candidate_pool_order_exclusion_and_k(client: TestClient, auth: dict[str, str]) -> None:
    pool = [11, 22, 33, 44, 55, 66]
    for path, base in (
        ("/internal/group-recommendations", GROUP_BASE),
        ("/internal/user-recommendations", USER_BASE),
    ):
        body = {**base, "candidateMovieIds": pool, "excludeMovieIds": [22, 55], "k": 3}
        r = client.post(path, json=body, headers=auth)
        assert r.status_code == 200
        assert [i["movieId"] for i in r.json()["results"]] == [11, 33, 44]
        # k larger than the pool returns the whole remaining pool, nothing else.
        r = client.post(path, json={**body, "k": 50}, headers=auth)
        assert [i["movieId"] for i in r.json()["results"]] == [11, 33, 44, 66]
        # Empty pool means nothing is eligible.
        r = client.post(path, json={**base, "candidateMovieIds": []}, headers=auth)
        assert r.json()["results"] == []


def test_candidate_movie_ids_validation(client: TestClient, auth: dict[str, str]) -> None:
    for bad in ([0], [-5], ["abc"], 42):
        for path, base in (
            ("/internal/group-recommendations", GROUP_BASE),
            ("/internal/user-recommendations", USER_BASE),
        ):
            r = client.post(path, json={**base, "candidateMovieIds": bad}, headers=auth)
            assert r.status_code == 422, (path, bad)
