"""Postgres connections for the read-only ml_reader role (ML_DATABASE_URL).

Every database read goes through here so hosted settings live in one place:
- TLS: `sslmode=require` is added for any non-local host (Supabase) unless the URL sets its own
  sslmode; local hosts keep libpq's default.
- Poolers: prepared statements are off (`prepare_threshold=None`), so the same code works through
  Supabase's session pooler (5432) and transaction pooler (6543).
- `application_name` marks the connections in `pg_stat_activity`.

`connect()` opens one connection, for the hourly background loaders (catalog, ratings, friends).
`Pool` keeps a few open for per-request queries (taste lookups), so a request doesn't pay for a
new TLS handshake.
"""

from __future__ import annotations

import threading
from typing import Any

CONNECT_TIMEOUT_SECONDS = 5
APPLICATION_NAME = "filmfuse-ml"
LOCAL_HOSTS = frozenset({"", "localhost", "127.0.0.1", "::1"})
POOL_MIN_SIZE = 1
POOL_MAX_SIZE = 4
POOL_TIMEOUT_SECONDS = 5.0  # wait for a free connection before giving up


def conninfo_for(url: str) -> str:
    """`url` plus the hosted defaults (sslmode, application_name), as a libpq conninfo string.
    Settings already in the URL win."""
    from psycopg.conninfo import conninfo_to_dict, make_conninfo

    params = conninfo_to_dict(url)
    extra: dict[str, Any] = {}
    host = str(params.get("host", ""))
    # A unix socket path (starts with "/") or a loopback host is local.
    local = host.startswith("/") or all(h.strip() in LOCAL_HOSTS for h in host.split(","))
    if not local and "sslmode" not in params:
        extra["sslmode"] = "require"
    if "application_name" not in params:
        extra["application_name"] = APPLICATION_NAME
    if "connect_timeout" not in params:
        extra["connect_timeout"] = CONNECT_TIMEOUT_SECONDS
    return make_conninfo(url, **extra)


def connect(url: str):  # pragma: no cover - needs a real database
    import psycopg

    return psycopg.connect(conninfo_for(url), prepare_threshold=None)


class Pool:
    """A small, lazily opened psycopg pool. `connection()` is a context manager."""

    def __init__(self, url: str, min_size: int = POOL_MIN_SIZE, max_size: int = POOL_MAX_SIZE):
        self._url = url
        self._min, self._max = min_size, max_size
        self._pool = None
        self._lock = threading.Lock()

    def _get(self):  # pragma: no cover - needs a real database
        with self._lock:
            if self._pool is None:
                from psycopg_pool import ConnectionPool

                self._pool = ConnectionPool(
                    conninfo_for(self._url),
                    min_size=self._min,
                    max_size=self._max,
                    kwargs={"prepare_threshold": None},
                    timeout=POOL_TIMEOUT_SECONDS,
                    # Drop connections the pooler or network closed, before handing them out.
                    check=ConnectionPool.check_connection,
                    name="ml-reader",
                    open=True,
                )
            return self._pool

    def connection(self):  # pragma: no cover - needs a real database
        return self._get().connection()

    def close(self) -> None:
        with self._lock:
            pool, self._pool = self._pool, None
        if pool is not None:  # pragma: no cover - needs a real database
            pool.close()
