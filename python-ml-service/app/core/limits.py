"""Request body cap for every route (pure ASGI, so it also covers chunked bodies)."""

from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

TOO_LARGE = "Request body too large"


class _BodyTooLarge(HTTPException):
    """An HTTPException, so FastAPI's body parsing re-raises it as-is.

    FastAPI turns any other exception raised while reading the body into a 400.
    """

    def __init__(self) -> None:
        super().__init__(status_code=413, detail=TOO_LARGE)


class BodySizeLimitMiddleware:
    """413 for a Content-Length over ``max_bytes``, or once a body without one passes it."""

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        if max_bytes < 1:
            raise ValueError("max_bytes must be positive")
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        for name, value in scope.get("headers", []):
            if name == b"content-length":
                try:
                    declared = int(value)
                except ValueError:
                    await _reject(scope, receive, send, 400, "Invalid Content-Length")
                    return
                if declared > self.max_bytes:
                    await _reject(scope, receive, send, 413, TOO_LARGE)
                    return

        received = 0
        started = False

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _BodyTooLarge()
            return message

        async def tracking_send(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, tracking_send)
        except _BodyTooLarge:
            if started:
                raise
            await _reject(scope, receive, send, 413, TOO_LARGE)


async def _reject(scope: Scope, receive: Receive, send: Send, status: int, detail: str) -> None:
    response = JSONResponse({"detail": detail}, status_code=status, headers={"Connection": "close"})
    await response(scope, receive, send)
