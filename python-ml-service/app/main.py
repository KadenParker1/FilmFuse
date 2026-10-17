"""FilmFuse ML service entrypoint."""

from fastapi import FastAPI

from app.api.routes import router
from app.core.config import get_settings
from app.core.limits import BodySizeLimitMiddleware


def create_app() -> FastAPI:
    # Fail fast at startup if ML_INTERNAL_KEY is missing outside ENV=local.
    settings = get_settings()
    docs = settings.docs_enabled
    app = FastAPI(
        title="FilmFuse ML service",
        version="0.1.0",
        docs_url="/docs" if docs else None,
        redoc_url="/redoc" if docs else None,
        openapi_url="/openapi.json" if docs else None,
    )
    app.add_middleware(BodySizeLimitMiddleware, max_bytes=settings.max_body_bytes)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(router)
    return app


app = create_app()
