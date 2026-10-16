"""Service configuration, read from environment variables."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

LOCAL_DEV_INTERNAL_KEY = "local-dev-internal-key"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: Literal["local", "test", "production"] = Field(default="production", alias="ENV")
    ml_internal_key: str | None = Field(default=None, alias="ML_INTERNAL_KEY")
    # Read-only role (ml_reader). Not used yet; optional until the algorithm needs the DB.
    ml_database_url: str | None = Field(default=None, alias="ML_DATABASE_URL")

    @model_validator(mode="after")
    def _require_internal_key(self) -> "Settings":
        if not self.ml_internal_key:
            if self.env == "local":
                self.ml_internal_key = LOCAL_DEV_INTERNAL_KEY
            else:
                raise ValueError("ML_INTERNAL_KEY must be set unless ENV=local")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
