"""FilmFuse ML service entrypoint."""

from fastapi import FastAPI

from app.api.routes import router
from app.core.config import get_settings


def create_app() -> FastAPI:
    # Fail fast at startup if ML_INTERNAL_KEY is missing outside ENV=local.
    get_settings()
    app = FastAPI(title="FilmFuse ML service", version="0.1.0")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(router)
    return app


app = create_app()
