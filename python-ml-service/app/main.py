"""FilmFuse ML service entrypoint."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.core.config import get_settings
from app.core.limits import BodySizeLimitMiddleware


@asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    # Load the movie catalog and build the CF model in the background, so the first request
    # doesn't pay for them.
    from app.services.recommender import get_recommender

    recommender = get_recommender()
    for part in ("catalog", "ratings", "friends"):  # catalog, CF model, friend data
        warm = getattr(getattr(recommender, part, None), "warm", None)
        if callable(warm):
            warm()
    yield
    close = getattr(getattr(recommender, "tastes", None), "close", None)
    if callable(close):
        close()  # the taste store's connection pool


def create_app() -> FastAPI:
    # Fail fast at startup if ML_INTERNAL_KEY is missing, or shorter than 32 characters,
    # outside ENV=local.
    settings = get_settings()
    docs = settings.docs_enabled
    app = FastAPI(
        title="FilmFuse ML service",
        version="0.1.0",
        docs_url="/docs" if docs else None,
        redoc_url="/redoc" if docs else None,
        openapi_url="/openapi.json" if docs else None,
        lifespan=_lifespan,
    )
    app.add_middleware(BodySizeLimitMiddleware, max_bytes=settings.max_body_bytes)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(router)

    return app


app = create_app()
