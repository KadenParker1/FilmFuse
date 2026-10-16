import pytest
from pydantic import ValidationError

from app.core.config import LOCAL_DEV_INTERNAL_KEY, Settings


def test_key_required_outside_local(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "production")
    monkeypatch.delenv("ML_INTERNAL_KEY", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_local_gets_dev_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "local")
    monkeypatch.delenv("ML_INTERNAL_KEY", raising=False)
    assert Settings(_env_file=None).ml_internal_key == LOCAL_DEV_INTERNAL_KEY
