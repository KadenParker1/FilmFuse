import pytest
from pydantic import ValidationError

from app.core.config import LOCAL_DEV_INTERNAL_KEY, MIN_INTERNAL_KEY_LENGTH, Settings


def test_key_required_outside_local(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "production")
    monkeypatch.delenv("ML_INTERNAL_KEY", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_local_gets_dev_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "local")
    monkeypatch.delenv("ML_INTERNAL_KEY", raising=False)
    assert Settings(_env_file=None).ml_internal_key == LOCAL_DEV_INTERNAL_KEY


@pytest.mark.parametrize("env", ["production", "test"])
def test_short_key_refused_outside_local(monkeypatch: pytest.MonkeyPatch, env: str) -> None:
    monkeypatch.setenv("ENV", env)
    short = "k" * (MIN_INTERNAL_KEY_LENGTH - 1)
    monkeypatch.setenv("ML_INTERNAL_KEY", short)
    with pytest.raises(ValidationError) as caught:
        Settings(_env_file=None)
    assert "at least 32 characters" in str(caught.value)
    assert short not in str(caught.value)  # the key itself is never echoed


def test_whitespace_padding_does_not_count(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "production")
    monkeypatch.setenv("ML_INTERNAL_KEY", "short-key" + " " * 40)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_thirty_two_characters_are_enough(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "production")
    key = "k" * MIN_INTERNAL_KEY_LENGTH
    monkeypatch.setenv("ML_INTERNAL_KEY", key)
    assert Settings(_env_file=None).ml_internal_key == key


def test_local_accepts_a_short_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ENV", "local")
    monkeypatch.setenv("ML_INTERNAL_KEY", "dev")
    assert Settings(_env_file=None).ml_internal_key == "dev"
