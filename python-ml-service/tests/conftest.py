from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings

TEST_KEY = "test-internal-key"
USER_A = "11111111-1111-1111-1111-111111111111"
USER_B = "22222222-2222-2222-2222-222222222222"


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> Iterator[TestClient]:
    monkeypatch.setenv("ENV", "test")
    monkeypatch.setenv("ML_INTERNAL_KEY", TEST_KEY)
    monkeypatch.delenv("ML_DATABASE_URL", raising=False)
    get_settings.cache_clear()
    from app.main import create_app

    yield TestClient(create_app())
    get_settings.cache_clear()


@pytest.fixture
def auth() -> dict[str, str]:
    return {"X-Internal-Key": TEST_KEY}
