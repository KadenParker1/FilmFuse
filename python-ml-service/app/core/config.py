"""Service configuration, read from environment variables."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

LOCAL_DEV_INTERNAL_KEY = "local-dev-internal-key"
# Outside ENV=local the shared secret must be at least this long (SECURITY.md #20). core-api's
# ProductionConfigValidator enforces the same minimum. Generate one with `openssl rand -hex 32`.
MIN_INTERNAL_KEY_LENGTH = 32
# Bodies come only from core-api; the largest is a heavy rater's user-vectors request (~100 bytes
# per rating), so 5 MiB is ample headroom while still refusing a runaway or hostile payload.
DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: Literal["local", "test", "production"] = Field(default="production", alias="ENV")
    ml_internal_key: str | None = Field(default=None, alias="ML_INTERNAL_KEY")
    # Read-only role (ml_reader). Not used yet; optional until the algorithm needs the DB.
    ml_database_url: str | None = Field(default=None, alias="ML_DATABASE_URL")
    max_body_bytes: int = Field(default=DEFAULT_MAX_BODY_BYTES, alias="ML_MAX_BODY_BYTES", ge=1024)

    @property
    def docs_enabled(self) -> bool:
        """/docs, /redoc and /openapi.json: off in production (a free map of every route)."""
        return self.env != "production"

    @model_validator(mode="after")
    def _require_internal_key(self) -> "Settings":
        if not self.ml_internal_key:
            if self.env == "local":
                self.ml_internal_key = LOCAL_DEV_INTERNAL_KEY
            else:
                raise ValueError("ML_INTERNAL_KEY must be set unless ENV=local")
        elif self.env != "local" and len(self.ml_internal_key.strip()) < MIN_INTERNAL_KEY_LENGTH:
            # Never echo the key itself.
            raise ValueError(
                f"ML_INTERNAL_KEY must be at least {MIN_INTERNAL_KEY_LENGTH} characters"
                " unless ENV=local"
            )
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
