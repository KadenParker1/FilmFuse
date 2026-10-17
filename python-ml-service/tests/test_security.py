"""Production hardening: no API docs in production, request body cap, /health stays public."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from tests.conftest import TEST_KEY

DOC_PATHS = ["/docs", "/redoc", "/openapi.json"]


def _client(monkeypatch: pytest.MonkeyPatch, env: str, **extra: str) -> TestClient:
    monkeypatch.setenv("ENV", env)
    monkeypatch.setenv("ML_INTERNAL_KEY", TEST_KEY)
    for name, value in extra.items():
        monkeypatch.setenv(name, value)
    get_settings.cache_clear()
    from app.main import create_app

    return TestClient(create_app())


@pytest.fixture
def prod_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    yield _client(monkeypatch, "production")
    get_settings.cache_clear()


@pytest.fixture
def small_limit_client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    yield _client(monkeypatch, "test", ML_MAX_BODY_BYTES="2048")
    get_settings.cache_clear()


@pytest.mark.parametrize("path", DOC_PATHS)
def test_docs_are_disabled_in_production(prod_client: TestClient, path: str) -> None:
    assert prod_client.get(path).status_code == 404


@pytest.mark.parametrize("path", DOC_PATHS)
def test_docs_are_available_off_production(client: TestClient, path: str) -> None:
    assert client.get(path).status_code == 200


def test_health_is_public_in_production(prod_client: TestClient) -> None:
    r = prod_client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_internal_routes_still_need_the_key_in_production(prod_client: TestClient) -> None:
    assert prod_client.post("/internal/user-vectors", json={}).status_code == 401


def test_env_defaults_to_production_so_docs_fail_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import Settings

    monkeypatch.delenv("ENV", raising=False)
    monkeypatch.setenv("ML_INTERNAL_KEY", TEST_KEY)
    assert Settings(_env_file=None).docs_enabled is False


def test_declared_oversized_body_is_413(small_limit_client: TestClient) -> None:
    body = b'{"userId": "' + b"x" * 4096 + b'"}'
    r = small_limit_client.post(
        "/internal/user-vectors",
        content=body,
        headers={"X-Internal-Key": TEST_KEY, "Content-Type": "application/json"},
    )
    assert r.status_code == 413
    assert r.json() == {"detail": "Request body too large"}


def test_oversized_body_is_413_even_before_auth(small_limit_client: TestClient) -> None:
    r = small_limit_client.post("/internal/user-vectors", content=b"x" * 4096)
    assert r.status_code == 413


def test_chunked_oversized_body_is_413(small_limit_client: TestClient) -> None:
    def chunks() -> Iterator[bytes]:
        yield b'{"userId": "'
        for _ in range(10):
            yield b"x" * 512
        yield b'"}'

    # A generator body is sent with Transfer-Encoding: chunked and no Content-Length.
    r = small_limit_client.post(
        "/internal/user-vectors",
        content=chunks(),
        headers={"X-Internal-Key": TEST_KEY, "Content-Type": "application/json"},
    )
    assert r.status_code == 413


def test_body_under_the_limit_is_processed(small_limit_client: TestClient) -> None:
    r = small_limit_client.post(
        "/internal/calibration-candidates",
        json={"userId": "11111111-1111-1111-1111-111111111111", "k": 3},
        headers={"X-Internal-Key": TEST_KEY},
    )
    assert r.status_code == 200
    assert len(r.json()["movieIds"]) <= 3


def test_invalid_content_length_is_400(small_limit_client: TestClient) -> None:
    r = small_limit_client.post(
        "/internal/user-vectors", content=b"{}", headers={"Content-Length": "abc"}
    )
    assert r.status_code == 400


def test_default_limit_is_five_mebibytes(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.core.config import Settings

    monkeypatch.setenv("ENV", "test")
    monkeypatch.setenv("ML_INTERNAL_KEY", TEST_KEY)
    monkeypatch.delenv("ML_MAX_BODY_BYTES", raising=False)
    assert Settings(_env_file=None).max_body_bytes == 5 * 1024 * 1024
