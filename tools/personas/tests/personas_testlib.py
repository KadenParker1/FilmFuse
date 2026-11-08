"""Test helpers for the persona tooling: sample data, a tiny fake MovieLens, a fake local stack."""

from __future__ import annotations

import copy
import json
import re
import sys
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

HERE = Path(__file__).resolve().parent
PERSONAS_DIR = HERE.parent
sys.path.insert(0, str(PERSONAS_DIR))

SAMPLE = HERE / "fixtures" / "personas.resolved.sample.json"
SUPA = "http://supa.test"
API = "http://api.test/api/v1"


def sample() -> dict:
    return json.loads(SAMPLE.read_text(encoding="utf-8"))


def write_movielens(directory: Path, movies: list[tuple[int, str, str, int, int]]) -> Path:
    """movies: (movieId, 'Title (Year)', 'Genre|Genre', tmdbId, rating count)."""
    directory.mkdir(parents=True, exist_ok=True)
    with open(directory / "movies.csv", "w", encoding="utf-8", newline="") as f:
        f.write("movieId,title,genres\n")
        for ml, title, genres, _, _ in movies:
            f.write(f'{ml},"{title}",{genres}\n')
    with open(directory / "links.csv", "w", encoding="utf-8") as f:
        f.write("movieId,imdbId,tmdbId\n")
        for ml, _, _, tmdb, _ in movies:
            f.write(f"{ml},0,{tmdb}\n")
    with open(directory / "ratings.csv", "w", encoding="utf-8") as f:
        f.write("userId,movieId,rating,timestamp\n")
        for ml, _, _, _, count in movies:
            for u in range(count):
                f.write(f"{u + 1},{ml},4.0,1000\n")
    return directory


def fake_movielens(directory: Path, n_per_genre: int = 40) -> Path:
    """A synthetic MovieLens with n movies per genre (single-genre movies), varied popularity."""
    genres = ["Horror", "Romance", "Animation", "Children", "Drama", "Crime", "Comedy", "Sci-Fi"]
    rows = []
    ml = 1
    for gi, g in enumerate(genres):
        for i in range(n_per_genre):
            year = 1960 + (i * 3) % 60
            rows.append((ml, f"{g} Movie {i} ({year})", g, 100_000 + ml, 1 + (i * 7 + gi) % 30))
            ml += 1
    return write_movielens(directory, rows)


class FakeStack:
    """In-memory Supabase Auth + core-api, as a seed.Transport. Records every call."""

    def __init__(self, catalog: dict[int, dict] | None = None):
        self.calls: list[tuple[str, str, str | None]] = []  # (method, path, user email)
        self.users: dict[str, dict] = {}  # email -> {id, password}
        self.tokens: dict[str, str] = {}  # token -> user id
        self.profiles: dict[str, dict] = {}
        self.prefs: dict[str, dict] = {}
        self.ratings: dict[str, dict[int, dict]] = {}
        self.friends: set[frozenset] = set()
        self.close: set[tuple[str, str]] = set()
        self.pending: set[tuple[str, str]] = set()
        self.blocks: set[tuple[str, str]] = set()
        self.groups: dict[str, dict] = {}
        self.catalog = catalog if catalog is not None else {}
        self.inject: list[tuple[str, str, int, dict]] = []  # (method, path regex, status, headers)
        self.session_results: list[dict] = []

    # -- helpers
    def email_of(self, uid: str | None) -> str | None:
        return next((e for e, u in self.users.items() if u["id"] == uid), None)

    def calls_for(self, email: str) -> list[tuple[str, str]]:
        return [(m, p) for m, p, e in self.calls if e == email]

    def add_movies(self, *ids: int, title: str = "Movie") -> None:
        for i in ids:
            self.catalog[i] = {
                "id": i,
                "title": f"{title} {i}",
                "releaseYear": 2000,
                "genreIds": [18],
            }

    # -- transport
    def __call__(self, method, url, headers, body):
        parts = urlsplit(url)
        path = parts.path
        query = {k: v[0] for k, v in parse_qs(parts.query).items()}
        data = json.loads(body) if body else None
        token = (headers.get("Authorization") or "").removeprefix("Bearer ") or None
        uid = self.tokens.get(token) if token else None
        self.calls.append(
            (
                method,
                path + (f"?{parts.query}" if parts.query else ""),
                self.email_of(uid) or (data or {}).get("email"),
            )
        )
        for i, (m, pattern, status, h) in enumerate(self.inject):
            if m == method and re.fullmatch(pattern, path):
                del self.inject[i]
                return status, h, json.dumps({"message": "Too many requests"}).encode()
        status, resp = self.route(method, path, query, data, uid)
        raw = b"" if resp is None else json.dumps(resp).encode()
        return status, {"content-type": "application/json"}, raw

    def _login(self, email):
        token = uuid.uuid4().hex
        self.tokens[token] = self.users[email]["id"]
        return 200, {"access_token": token, "user": {"id": self.users[email]["id"]}}

    def route(self, method, path, q, data, uid):  # noqa: C901 - a fake router
        if path == "/auth/v1/signup":
            if data["email"] in self.users:
                return 422, {"code": "user_already_exists", "msg": "User already registered"}
            self.users[data["email"]] = {"id": str(uuid.uuid4()), "password": data["password"]}
            return self._login(data["email"])
        if path == "/auth/v1/token":
            u = self.users.get(data["email"])
            if not u or u["password"] != data["password"]:
                return 400, {"error_code": "invalid_credentials"}
            return self._login(data["email"])
        if path == "/actuator/health":
            return 200, {"status": "UP"}
        assert path.startswith("/api/v1"), path
        p = path.removeprefix("/api/v1")
        if uid is None:
            return 401, {"message": "Unauthorized"}
        prof = self.profiles.setdefault(
            uid,
            {
                "id": uid,
                "username": None,
                "displayName": None,
                "bio": None,
                "onboardingCompleted": False,
            },
        )
        if p == "/me":
            if method == "PATCH":
                taken = any(
                    o["username"] == data.get("username") and o["id"] != uid
                    for o in self.profiles.values()
                )
                if taken:
                    return 409, {"message": "That username is already taken."}
                prof.update(data)
            return 200, prof
        if p.startswith("/me/preferences/") or p == "/me/settings/comfort":
            self.prefs.setdefault(uid, {})[p] = copy.deepcopy(data)
            return 200, data
        if p == "/me/onboarding/complete":
            prof["onboardingCompleted"] = True
            return 200, {"onboardingCompletedAt": "now"}
        if m := re.fullmatch(r"/movies/(\d+)", p):
            movie = self.catalog.get(int(m.group(1)))
            if not movie:
                return 404, {"message": "Movie not found"}
            return 200, {**movie, "genres": [{"id": g, "name": "x"} for g in movie["genreIds"]]}
        if p == "/movies/search":
            items = [m for m in self.catalog.values() if q["q"].lower() in m["title"].lower()]
            return 200, {"items": items, "nextCursor": None}
        if p == "/movies/popular":
            return 200, {"items": list(self.catalog.values()), "nextCursor": None}
        if m := re.fullmatch(r"/ratings/(\d+)", p):
            mid = int(m.group(1))
            if mid not in self.catalog:
                return 404, {"message": "Movie not found"}
            self.ratings.setdefault(uid, {})[mid] = data
            return 200, {"id": "r", "movie": self.catalog[mid], **data}
        if p == "/me/ratings":
            items = [
                {
                    "movie": self.catalog[mid],
                    "score": r["score"],
                    "visibility": r["visibility"],
                    "body": r.get("body"),
                }
                for mid, r in self.ratings.get(uid, {}).items()
            ]
            return 200, {"items": items, "nextCursor": None}
        if p == "/friends":
            items = []
            for pair in self.friends:
                if uid in pair:
                    other = next(x for x in pair if x != uid)
                    items.append({"id": other, "closeFriend": (uid, other) in self.close})
            return 200, {"items": items, "nextCursor": None}
        if m := re.fullmatch(r"/friends/([0-9a-f-]+)", p):
            other = m.group(1)
            if frozenset((uid, other)) not in self.friends:
                return 404, {"message": "Not friends"}
            (self.close.add if data["closeFriend"] else self.close.discard)((uid, other))
            return 200, {"id": other, "closeFriend": data["closeFriend"]}
        if p == "/friends/requests":
            if method == "GET":
                items = [
                    {"user": {"id": b}}
                    for a, b in self.pending
                    if a == uid and q["direction"] == "OUTGOING"
                ]
                return 200, items
            target = data["targetUserId"]
            if frozenset((uid, target)) in self.friends or (uid, target) in self.blocks:
                return 409, {"message": "Already friends"}
            if (target, uid) in self.pending:
                self.pending.discard((target, uid))
                self.friends.add(frozenset((uid, target)))
                return 200, {"userId": target, "friendship": "FRIENDS"}
            self.pending.add((uid, target))
            return 200, {"userId": target, "friendship": "REQUEST_SENT"}
        if m := re.fullmatch(r"/friends/requests/([0-9a-f-]+)/accept", p):
            requester = m.group(1)
            if (requester, uid) not in self.pending:
                return 404, {"message": "No pending request"}
            self.pending.discard((requester, uid))
            self.friends.add(frozenset((uid, requester)))
            return 200, {"userId": requester, "friendship": "FRIENDS"}
        if p == "/blocks":
            return 200, [{"id": b} for a, b in self.blocks if a == uid]
        if m := re.fullmatch(r"/blocks/([0-9a-f-]+)", p):
            self.blocks.add((uid, m.group(1)))
            self.friends.discard(frozenset((uid, m.group(1))))
            return 204, None
        if p == "/groups":
            if method == "GET":
                return 200, [
                    {
                        "id": gid,
                        "name": g["name"],
                        "role": "OWNER" if g["owner"] == uid else "MEMBER",
                    }
                    for gid, g in self.groups.items()
                    if uid in g["members"]
                ]
            for mid in data["memberIds"]:
                if frozenset((uid, mid)) not in self.friends:
                    return 400, {"message": "You can only add your friends to a group."}
            gid = str(uuid.uuid4())
            self.groups[gid] = {
                "name": data["name"],
                "owner": uid,
                "members": {uid: "ACTIVE", **{m: "INVITED" for m in data["memberIds"]}},
            }
            return 201, {"id": gid}
        if m := re.fullmatch(r"/groups/([0-9a-f-]+)", p):
            g = self.groups[m.group(1)]
            return 200, {
                "members": [{"user": {"id": k}, "status": s} for k, s in g["members"].items()]
            }
        if m := re.fullmatch(r"/groups/([0-9a-f-]+)/invites/accept", p):
            self.groups[m.group(1)]["members"][uid] = "ACTIVE"
            return 200, {}
        if p == "/sessions":
            return 201, {
                "id": str(uuid.uuid4()),
                "results": copy.deepcopy(self.session_results),
                "effectiveFilter": {"label": "Anything"},
                "members": [],
                "hiddenResultCount": 0,
            }
        return 404, {"message": f"fake: no route {method} {p}"}
