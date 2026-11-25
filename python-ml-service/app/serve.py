"""Container entrypoint: serve the app on one dual-stack (IPv6 + IPv4) socket.

`uvicorn --host ::` gives an IPv6-only socket (asyncio sets IPV6_V6ONLY), so plain IPv4 clients,
such as a health check, are refused. Railway's private network can be IPv6-only, while other
callers may use IPv4, so the socket must take both. uvicorn accepts ready-made sockets.

    python -m app.serve            # port from $PORT (default 8000)
"""

from __future__ import annotations

import os
import socket

DEFAULT_PORT = 8000


def dual_stack_socket(port: int, host: str = "::") -> socket.socket:
    """A listening-ready TCP socket on `host`:`port` that accepts IPv6 and IPv4-mapped clients.
    Falls back to IPv4 only where the kernel has IPv6 disabled."""
    if socket.has_ipv6:
        try:
            sock = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((host, port))
            return sock
        except OSError:
            sock.close()
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))  # noqa: S104 - a container must listen on every interface
    return sock


def main() -> None:  # pragma: no cover - starts a server
    import uvicorn

    port = int(os.environ.get("PORT") or DEFAULT_PORT)
    sock = dual_stack_socket(port)
    config = uvicorn.Config("app.main:app")
    uvicorn.Server(config).run(sockets=[sock])


if __name__ == "__main__":
    main()
