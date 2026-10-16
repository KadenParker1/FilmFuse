"""Shared-secret auth for /internal/* routes (core-api -> ML service)."""

import hmac
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status

from app.core.config import Settings, get_settings


def require_internal_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_key: Annotated[str | None, Header(alias="X-Internal-Key")] = None,
) -> None:
    if x_internal_key is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="Missing X-Internal-Key")
    expected = settings.ml_internal_key or ""
    if not hmac.compare_digest(x_internal_key.encode(), expected.encode()):
        raise HTTPException(status.HTTP_403_FORBIDDEN, detail="Invalid X-Internal-Key")
