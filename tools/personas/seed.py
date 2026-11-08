#!/usr/bin/env python3
"""Seed the test personas and their fake social networks into the LOCAL stack (stdlib only).

    ANON_KEY=... python3 -I tools/personas/seed.py [--only personas|network|all] [--keys k1,k2]
                                                   [--dry-run] [--preflight] [--concurrency N]

Everything goes through the real APIs - Supabase Auth (:55321) and core-api (:8090/api/v1) -
never direct SQL. Re-running is safe: existing accounts sign in, unchanged ratings are skipped,
existing friendships / requests / blocks / groups are detected first, 409s are explained.

Per persona: sign up (or sign in) -> GET /me -> PATCH /me -> PUT genre / mood / favourite prefs
-> PUT comfort -> POST onboarding/complete (unless onboarded: false) -> PUT /ratings/{id} for the
anchors plus the SYNTHETIC generated ratings (out/ratings.generated.json, see generate.py).
Movies the local catalog doesn't know (404) are skipped and reported. Then the network from
networks.json: friend requests + accepts, close-friend flags, pending requests, blocks, groups.

Writes out/seed-state.json (persona key -> user id, group name -> id). Local use only.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402
import networks as networks_mod  # noqa: E402

PUBLIC_SHARE = 0.20
PRIVATE_SHARE = 0.10
NOTE_SHARE = 0.40  # of PUBLIC ratings
MIN_FAVORITES = 3
MAX_FAVORITES = 10
FIXTURE_PROBE_IDS = (278, 238, 155)  # in core-api's offline fixture catalog
DEFAULT_RETRY_WAIT = 10.0
MAX_RETRY_WAIT = 120.0

# A transport: (method, url, headers, body bytes | None) -> (status, lower-case headers, body bytes)
Transport = Callable[[str, str, dict, bytes | None], tuple[int, dict, bytes]]


class SeedError(Exception):
    pass


def urllib_transport(
    method: str, url: str, headers: dict, body: bytes | None
) -> tuple[int, dict, bytes]:
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in (e.headers or {}).items()}, e.read()
    except urllib.error.URLError as e:
        raise SeedError(f"cannot reach {url}: {e.reason}") from e


def retry_after_seconds(headers: dict, attempt: int) -> float:
    raw = headers.get("retry-after")
    if raw:
        try:
            return min(MAX_RETRY_WAIT, max(0.0, float(raw)))
        except ValueError:
            pass
    return min(MAX_RETRY_WAIT, DEFAULT_RETRY_WAIT * (2**attempt))  # Supabase Auth sends none


class Api:
    """HTTP helper: JSON in/out, 429 handling (Retry-After, then exponential backoff)."""

    def __init__(
        self,
        transport: Transport,
        *,
        supabase: str,
        api: str,
        anon_key: str,
        sleeper: Callable[[float], None] = time.sleep,
        max_retries: int = 6,
        log: Callable[[str], None] = print,
    ):
        self.transport = transport
        self.supabase = supabase.rstrip("/")
        self.api = api.rstrip("/")
        self.anon_key = anon_key
        self.sleeper = sleeper
        self.max_retries = max_retries
        self.log = log
        self.rate_limited = 0

    def request(
        self,
        method: str,
        url: str,
        body=None,
        token: str | None = None,
        headers: dict | None = None,
    ):
        status, data, _ = self.request_full(method, url, body, token, headers)
        return status, data

    def request_full(
        self,
        method: str,
        url: str,
        body=None,
        token: str | None = None,
        headers: dict | None = None,
        retries: int | None = None,
    ):
        """(status, decoded body, response headers); 429s are retried `retries` times."""
        retries = self.max_retries if retries is None else retries
        h = {"Accept": "application/json"}
        if body is not None:
            h["Content-Type"] = "application/json"
        if token:
            h["Authorization"] = f"Bearer {token}"
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        attempt = 0
        while True:
            status, rh, raw = self.transport(method, url, h, data)
            if status == 429 and attempt < retries:
                wait = retry_after_seconds(rh, attempt)
                self.rate_limited += 1
                self.log(f"    429 rate limited on {method} {_short(url)}; waiting {wait:.0f}s")
                self.sleeper(wait)
                attempt += 1
                continue
            return status, _decode(raw), rh

    def core(self, method: str, path: str, body=None, token: str | None = None):
        return self.request(method, self.api + path, body, token)

    def auth(self, path: str, body: dict):
        return self.request(
            "POST", f"{self.supabase}/auth/v1{path}", body, headers={"apikey": self.anon_key}
        )


def _decode(raw: bytes):
    if not raw:
        return None
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text)
    except ValueError:
        return text


def _short(url: str) -> str:
    return urllib.parse.urlsplit(url).path


def _msg(body) -> str:
    if isinstance(body, dict):
        return str(
            body.get("message")
            or body.get("msg")
            or body.get("error_description")
            or body.get("error_code")
            or body.get("code")
            or body
        )
    return str(body)[:200]


# --------------------------------------------------------------------------- accounts


@dataclass
class Account:
    key: str
    email: str
    password: str
    token: str
    user_id: str
    created: bool = False


def sign_in(api: Api, email: str, password: str) -> tuple[str, str] | None:
    status, body = api.auth("/token?grant_type=password", {"email": email, "password": password})
    if status == 200 and isinstance(body, dict) and body.get("access_token"):
        return body["access_token"], body["user"]["id"]
    if status in (400, 401):  # invalid_credentials: no such account (or wrong password)
        return None
    raise SeedError(f"sign-in {email}: HTTP {status} {_msg(body)}")


def sign_up(api: Api, email: str, password: str) -> tuple[str, str] | None:
    status, body = api.auth("/signup", {"email": email, "password": password})
    if status == 200 and isinstance(body, dict):
        if body.get("access_token"):
            return body["access_token"], body["user"]["id"]
        raise SeedError(
            f"sign-up {email}: no session returned - local Supabase must have "
            "[auth.email] enable_confirmations = false"
        )
    text = json.dumps(body) if not isinstance(body, str) else body
    if status in (400, 422) and ("already" in text.lower() or "exists" in text.lower()):
        return None
    raise SeedError(f"sign-up {email}: HTTP {status} {_msg(body)}")


def ensure_account(
    api: Api, key: str, email: str, password: str, known: bool, create: bool = True
) -> Account:
    """Sign in an existing account or create it. `known` (in seed-state) tries sign-in first."""
    if known or not create:
        got = sign_in(api, email, password)
        if got:
            return Account(key, email, password, got[0], got[1])
        if not create:
            raise SeedError(f"{email}: no account - seed the personas first (--only personas)")
        got = sign_up(api, email, password)
        if got:
            return Account(key, email, password, got[0], got[1], created=True)
    else:
        got = sign_up(api, email, password)
        if got:
            return Account(key, email, password, got[0], got[1], created=True)
        got = sign_in(api, email, password)
        if got:
            return Account(key, email, password, got[0], got[1])
    raise SeedError(
        f"{email}: account exists but the password doesn't match (reset with `supabase db reset`)"
    )


# --------------------------------------------------------------------------- planning

NOTES: dict[str, dict[str, list[str]]] = {
    "parent": {
        "high": ["The kids asked to watch it again straight away.", "Family movie night winner."],
        "mid": ["Kept the kids busy, which is all I ask.", "Fine for a rainy Sunday."],
        "low": ["Turned it off before bedtime. Not for us.", "Too much for the little ones."],
    },
    "horror": {
        "high": ["Genuinely unsettling. Slept with the lights on.", "This is how you do dread."],
        "mid": ["A couple of good scares, a lot of filler.", "Solid creature work, weak ending."],
        "low": ["Not one real scare.", "Jump scares are a crutch."],
    },
    "critic": {
        "high": ["Earns every minute.", "Formally daring and it actually works."],
        "mid": ["Competent, forgettable.", "Half a great film."],
        "low": ["Overrated, and I will die on this hill.", "Style in search of a point."],
    },
    "romance": {
        "high": ["Cried, laughed, would watch again tonight.", "The chemistry!"],
        "mid": ["Cute but the third act dragged.", "Cozy enough."],
        "low": ["Where was the romance?", "Not my cup of tea."],
    },
    "teen": {
        "high": ["Literally the best thing ever.", "Watched it three times this week."],
        "mid": ["It was ok I guess.", "Kinda mid honestly."],
        "low": ["So boring.", "My parents made me watch this."],
    },
    "generic": {
        "high": ["Instant rewatch.", "Exactly my kind of movie.", "Still thinking about it."],
        "mid": ["Fine for a weeknight.", "Some great moments, some filler.", "Glad I saw it once."],
        "low": ["Not for me at all.", "I tried. I really tried.", "Hard pass."],
    },
}
TAG_VOICE = [
    ({"parent", "family"}, "parent"),
    ({"horror", "slasher"}, "horror"),
    ({"critic", "arthouse", "auteur", "harsh-rater", "contrarian"}, "critic"),
    ({"romance", "cozy"}, "romance"),
    ({"teen"}, "teen"),
]


def voice_for(persona: dict) -> str:
    tags = set(persona.get("tags", []))
    for wanted, voice in TAG_VOICE:
        if tags & wanted:
            return voice
    return "generic"


def band(score: int) -> str:
    return "high" if score >= 8 else ("low" if score <= 4 else "mid")


def planned_ratings(persona: dict, generated: dict) -> list[dict]:
    """Anchors then generated ratings, each with a deterministic visibility and optional note."""
    out: list[dict] = []
    seen: set[int] = set()
    rows = [dict(a, kind="anchor") for a in persona.get("anchorRatings", [])]
    rows += [dict(g, kind="generated") for g in generated.get(persona["key"], [])]
    voice = NOTES[voice_for(persona)]
    for row in rows:
        mid = common.movie_id(row)
        if mid is None or mid in seen:
            continue
        seen.add(mid)
        rng = random.Random(f"{persona['key']}:{mid}")
        roll = rng.random()
        visibility = (
            "PUBLIC"
            if roll < PUBLIC_SHARE
            else "PRIVATE"
            if roll < PUBLIC_SHARE + PRIVATE_SHARE
            else "FRIENDS"
        )
        body = None
        if visibility == "PUBLIC" and rng.random() < NOTE_SHARE:
            body = rng.choice(voice[band(int(row["score"]))])
        out.append(
            {
                "movieId": mid,
                "title": row.get("title"),
                "year": row.get("year"),
                "score": int(row["score"]),
                "visibility": visibility,
                "body": body,
                "kind": row["kind"],
            }
        )
    return out


def rating_payload(r: dict) -> dict:
    payload = {"score": r["score"], "visibility": r["visibility"], "source": "QUICK_RATE"}
    if r["body"]:
        payload["body"] = r["body"]
    return payload


def genre_payload(persona: dict) -> list[dict]:
    return [
        {"genreId": int(g), "score": int(s)}
        for g, s in sorted(persona.get("genres", {}).items(), key=lambda kv: int(kv[0]))
    ]


def mood_payload(persona: dict) -> list[dict]:
    return [{"moodTag": m, "score": int(s)} for m, s in sorted(persona.get("moods", {}).items())]


def profile_payload(persona: dict) -> dict:
    body = {"username": persona["username"], "displayName": persona["displayName"]}
    if persona.get("bio"):
        body["bio"] = persona["bio"][:300]
    return body


# --------------------------------------------------------------------------- seeding


@dataclass
class PersonaReport:
    key: str
    user_id: str | None = None
    created: bool = False
    ratings_written: int = 0
    ratings_unchanged: int = 0
    skipped_movies: list[str] = field(default_factory=list)
    fallback_favorites: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


class Seeder:
    def __init__(
        self,
        api: Api,
        data: dict,
        generated: dict,
        state: dict,
        *,
        concurrency: int = 4,
        log: Callable[[str], None] = print,
    ):
        self.api = api
        self.data = data
        self.generated = generated
        self.state = state
        self.state.setdefault("personas", {})
        self.state.setdefault("groups", {})
        self.concurrency = max(1, concurrency)
        self._log = log
        self._lock = threading.Lock()
        self.accounts: dict[str, Account] = {}
        self.reports: dict[str, PersonaReport] = {}
        self.network_log: list[str] = []
        self.network_errors: list[str] = []
        self.by_key = common.personas_by_key(data)

    def log(self, msg: str) -> None:
        with self._lock:
            self._log(msg)

    # -- accounts / calls
    def account(self, key: str, create: bool = True) -> Account:
        if key in self.accounts:
            return self.accounts[key]
        p = self.by_key[key]
        email = common.email_for(self.data, p)
        known = key in self.state["personas"]
        acct = ensure_account(self.api, key, email, self.data["password"], known, create)
        with self._lock:
            self.accounts[key] = acct
            self.state["personas"][key] = {
                "userId": acct.user_id,
                "email": email,
                "username": p["username"],
            }
        return acct

    def call(self, key: str, method: str, path: str, body=None):
        status, resp, _ = self.call_full(key, method, path, body)
        return status, resp

    def call_full(self, key: str, method: str, path: str, body=None, retries: int | None = None):
        acct = self.account(key)
        url = self.api.api + path
        status, resp, h = self.api.request_full(method, url, body, acct.token, retries=retries)
        if status == 401:  # token expired during a long run: sign in again once
            got = sign_in(self.api, acct.email, acct.password)
            if got:
                acct.token = got[0]
                status, resp, h = self.api.request_full(
                    method, url, body, acct.token, retries=retries
                )
        return status, resp, h

    def paged(self, key: str, path: str) -> list[dict]:
        items: list[dict] = []
        cursor = None
        for _ in range(200):
            sep = "&" if "?" in path else "?"
            url = path + (f"{sep}cursor={urllib.parse.quote(cursor)}" if cursor else "")
            status, body = self.call(key, "GET", url)
            if status != 200:
                raise SeedError(f"GET {path} as {key}: HTTP {status} {_msg(body)}")
            items += body.get("items", [])
            cursor = body.get("nextCursor")
            if not cursor:
                break
        return items

    # -- personas
    def seed_personas(self, personas: list[dict]) -> None:
        with ThreadPoolExecutor(max_workers=self.concurrency) as pool:
            for report in pool.map(self._seed_one_safe, personas):
                self.reports[report.key] = report

    def _seed_one_safe(self, persona: dict) -> PersonaReport:
        try:
            return self.seed_persona(persona)
        except (SeedError, KeyError, TypeError, ValueError) as e:  # unexpected shape: report, go on
            rep = self.reports.get(persona["key"]) or PersonaReport(persona["key"])
            rep.errors.append(str(e))
            self.log(f"[{persona['key']}] FAILED: {e}")
            return rep

    def seed_persona(self, p: dict) -> PersonaReport:
        key = p["key"]
        rep = PersonaReport(key)
        acct = self.account(key)
        rep.user_id, rep.created = acct.user_id, acct.created
        self.log(f"[{key}] {'created' if acct.created else 'signed in'} {acct.email}")

        status, me = self.call(key, "GET", "/me")
        if status != 200:
            raise SeedError(f"GET /me: HTTP {status} {_msg(me)}")
        want = profile_payload(p)
        if any(me.get(k) != v for k, v in want.items()):
            status, body = self.call(key, "PATCH", "/me", want)
            if status == 409:
                rep.errors.append(f"username {p['username']} is taken by another account")
            elif status != 200:
                rep.errors.append(f"PATCH /me: HTTP {status} {_msg(body)}")

        for path, payload in (
            ("/me/preferences/genres", genre_payload(p)),
            ("/me/preferences/moods", mood_payload(p)),
        ):
            status, body = self.call(key, "PUT", path, payload)
            if status != 200:
                rep.errors.append(f"PUT {path}: HTTP {status} {_msg(body)}")

        self._favorites(p, rep)

        try:
            comfort = common.normalise_comfort(p.get("comfort"))
        except ValueError as e:
            rep.errors.append(f"comfort: {e}")
        else:
            status, body = self.call(key, "PUT", "/me/settings/comfort", comfort)
            if status != 200:
                rep.errors.append(f"PUT comfort: HTTP {status} {_msg(body)}")

        if p.get("onboarded", True) and not me.get("onboardingCompleted"):
            status, body = self.call(key, "POST", "/me/onboarding/complete")
            if status != 200:
                rep.errors.append(f"onboarding/complete: HTTP {status} {_msg(body)}")

        self._ratings(p, rep)
        skipped = f", {len(rep.skipped_movies)} not in catalog" if rep.skipped_movies else ""
        self.log(
            f"[{key}] done: {rep.ratings_written} ratings written, "
            f"{rep.ratings_unchanged} unchanged{skipped}"
            + (f"; {len(rep.errors)} error(s)" if rep.errors else "")
        )
        return rep

    def _movie_exists(self, key: str, movie_id: int) -> bool:
        status, body = self.call(key, "GET", f"/movies/{movie_id}")
        if status == 200:
            return True
        if status == 404:
            return False
        raise SeedError(f"GET /movies/{movie_id}: HTTP {status} {_msg(body)}")

    def _favorites(self, p: dict, rep: PersonaReport) -> None:
        key = p["key"]
        chosen: list[int] = []
        missing: list[dict] = []
        for fav in p.get("favorites", [])[:MAX_FAVORITES]:
            mid = common.movie_id(fav)
            if mid is not None and self._movie_exists(key, mid):
                chosen.append(mid)
            else:
                missing.append(fav)
                rep.skipped_movies.append(f"favourite {common.title_year(fav)} [{mid}]")
        if len(chosen) < MIN_FAVORITES:
            for fav in missing:  # same title under another id in the local catalog?
                if len(chosen) >= MIN_FAVORITES:
                    break
                q = urllib.parse.quote(str(fav["title"])[:100])
                status, body = self.call(key, "GET", f"/movies/search?q={q}")
                for m in (body or {}).get("items", []) if status == 200 else []:
                    if (
                        m["title"].casefold() == str(fav["title"]).casefold()
                        and m["id"] not in chosen
                    ):
                        chosen.append(m["id"])
                        rep.fallback_favorites.append(f"{m['title']} [{m['id']}] (search)")
                        break
            reserved = common.reserved_ids(p)
            top = sorted(p.get("genres", {}).items(), key=lambda kv: -kv[1])[:1]
            paths = [f"/movies/popular?genre={int(g)}" for g, _ in top] + ["/movies/popular"]
            for path in paths:
                if len(chosen) >= MIN_FAVORITES:
                    break
                status, body = self.call(key, "GET", path)
                for m in (body or {}).get("items", []) if status == 200 else []:
                    if len(chosen) >= MIN_FAVORITES:
                        break
                    if m["id"] not in chosen and m["id"] not in reserved:
                        chosen.append(m["id"])
                        rep.fallback_favorites.append(f"{m['title']} [{m['id']}] (popular)")
            if rep.fallback_favorites:
                self.log(
                    f"[{key}] !!! FALLBACK FAVOURITES: only "
                    f"{len(chosen) - len(rep.fallback_favorites)} of the persona's favourites "
                    f"are in the local catalog; filled with {', '.join(rep.fallback_favorites)}"
                )
        if not chosen:
            rep.errors.append("no favourites could be set (empty catalog?)")
            return
        payload = [{"movieId": m, "rank": i + 1} for i, m in enumerate(chosen)]
        status, body = self.call(key, "PUT", "/me/preferences/favorites", payload)
        if status != 200:
            rep.errors.append(f"PUT favorites: HTTP {status} {_msg(body)}")

    def _ratings(self, p: dict, rep: PersonaReport) -> None:
        key = p["key"]
        planned = planned_ratings(p, self.generated)
        if not planned:
            return
        existing = {r["movie"]["id"]: r for r in self.paged(key, "/me/ratings")}
        for r in planned:
            cur = existing.get(r["movieId"])
            if (
                cur
                and cur["score"] == r["score"]
                and cur["visibility"] == r["visibility"]
                and (cur.get("body") or None) == r["body"]
            ):
                rep.ratings_unchanged += 1
                continue
            status, body = self.call(key, "PUT", f"/ratings/{r['movieId']}", rating_payload(r))
            if status == 200:
                rep.ratings_written += 1
            elif status == 404:
                rep.skipped_movies.append(
                    f"{r['kind']} {r['title']} ({r['year']}) [{r['movieId']}]"
                )
            else:
                rep.errors.append(f"PUT /ratings/{r['movieId']}: HTTP {status} {_msg(body)}")

    # -- network
    def seed_network(self, net: networks_mod.Network, keys: set[str] | None = None) -> None:
        def selected(*ks: str) -> bool:
            return keys is None or all(k in keys for k in ks)

        def note(msg: str) -> None:
            self.network_log.append(msg)
            self.log(f"  {msg}")

        def fail(msg: str) -> None:
            self.network_errors.append(msg)
            self.log(f"  ERROR {msg}")

        friend_cache: dict[str, dict[str, dict]] = {}

        def friends(k: str, refresh: bool = False) -> dict[str, dict]:
            if refresh or k not in friend_cache:
                friend_cache[k] = {f["id"]: f for f in self.paged(k, "/friends")}
            return friend_cache[k]

        def uid(k: str) -> str:
            return self.account(k, create=False).user_id

        self.log("network: friendships")
        for e in net.edges:
            if e.type != "friend" or not selected(e.a, e.b):
                continue
            if uid(e.b) not in friends(e.a):
                status, body = self.call(
                    e.a, "POST", "/friends/requests", {"targetUserId": uid(e.b)}
                )
                if status == 409:
                    fail(f"{e.a} -> {e.b}: 409 {_msg(body)} (already friends, or a block exists)")
                    continue
                if status != 200:
                    fail(f"{e.a} -> {e.b}: friend request HTTP {status} {_msg(body)}")
                    continue
                if body.get("friendship") != "FRIENDS":
                    status, body = self.call(e.b, "POST", f"/friends/requests/{uid(e.a)}/accept")
                    if status not in (200, 404):
                        fail(f"{e.b} accepts {e.a}: HTTP {status} {_msg(body)}")
                        continue
                friends(e.a, refresh=True)
                friend_cache.pop(e.b, None)
                note(f"friends: {e.a} + {e.b}")
            for me_, other in ((e.a, e.b), (e.b, e.a)):
                want = me_ in e.close_by
                current = friends(me_).get(uid(other))
                if current is None:
                    fail(f"{me_} and {other} should be friends but aren't")
                    continue
                if bool(current.get("closeFriend")) != want:
                    status, body = self.call(
                        me_, "PATCH", f"/friends/{uid(other)}", {"closeFriend": want}
                    )
                    if status == 200:
                        current["closeFriend"] = want
                        note(f"close friend: {me_} -> {other} = {want}")
                    else:
                        fail(f"close flag {me_} -> {other}: HTTP {status} {_msg(body)}")

        self.log("network: pending requests")
        for e in net.edges:
            if e.type != "pending" or not selected(e.a, e.b):
                continue
            if uid(e.b) in friends(e.a):
                fail(f"pending {e.a} -> {e.b}: they are already friends (reset to restore)")
                continue
            outgoing = self.call(e.a, "GET", "/friends/requests?direction=OUTGOING")[1] or []
            if any(r["user"]["id"] == uid(e.b) for r in outgoing):
                continue
            status, body = self.call(e.a, "POST", "/friends/requests", {"targetUserId": uid(e.b)})
            if status == 200:
                note(f"pending: {e.a} -> {e.b} ({body.get('friendship')})")
            else:
                fail(f"pending {e.a} -> {e.b}: HTTP {status} {_msg(body)}")

        self.log("network: blocks")
        for e in net.edges:
            if e.type != "block" or not selected(e.a, e.b):
                continue
            status, blocks = self.call(e.a, "GET", "/blocks")
            if status == 200 and any(u["id"] == uid(e.b) for u in blocks):
                continue
            status, body = self.call(e.a, "POST", f"/blocks/{uid(e.b)}")
            if status in (200, 204):
                note(f"block: {e.a} blocks {e.b}")
            else:
                fail(f"block {e.a} -> {e.b}: HTTP {status} {_msg(body)}")

        self.log("network: groups")
        for g in net.groups:
            people = [g["creator"], *g["members"]]
            if not selected(*people):
                continue
            self._group(g, uid, note, fail)

    def _group(self, g: dict, uid, note, fail) -> None:
        creator = g["creator"]
        status, mine = self.call(creator, "GET", "/groups")
        if status != 200:
            fail(f"group {g['name']}: GET /groups HTTP {status}")
            return
        existing = next(
            (x for x in mine if x["name"] == g["name"] and x.get("role") == "OWNER"), None
        )
        member_ids = [uid(m) for m in g["members"]]
        if existing is None:
            status, body = self.call(
                creator,
                "POST",
                "/groups",
                {"name": g["name"], "emoji": g.get("emoji"), "memberIds": member_ids},
            )
            if status != 201:
                fail(f"group {g['name']}: create HTTP {status} {_msg(body)}")
                return
            group_id = body["id"]
            note(f"group created: {g['name']}")
        else:
            group_id = existing["id"]
            status, detail = self.call(creator, "GET", f"/groups/{group_id}")
            present = {m["user"]["id"] for m in (detail or {}).get("members", [])}
            missing = [i for i in member_ids if i not in present]
            if missing:
                status, body = self.call(
                    creator, "POST", f"/groups/{group_id}/members", {"userIds": missing}
                )
                if status != 200:
                    fail(f"group {g['name']}: add members HTTP {status} {_msg(body)}")
        self.state["groups"][g["name"]] = {"id": group_id, "creator": creator}
        status, detail = self.call(creator, "GET", f"/groups/{group_id}")
        statuses = {m["user"]["id"]: m["status"] for m in (detail or {}).get("members", [])}
        for m in g["members"]:
            if statuses.get(uid(m)) == "ACTIVE":
                continue
            status, body = self.call(m, "POST", f"/groups/{group_id}/invites/accept")
            if status == 200:
                note(f"group {g['name']}: {m} joined")
            else:
                fail(f"group {g['name']}: {m} accept HTTP {status} {_msg(body)}")

    # -- preflight
    def preflight(self, personas: list[dict]) -> int:
        root = self.api.api.rsplit("/api/", 1)[0]
        status, body = self.api.request("GET", f"{root}/actuator/health")
        self.log(f"core-api health: HTTP {status} {_msg(body) if status != 200 else body}")
        probe_key = personas[0]["key"]
        sample: list[int] = []
        for p in personas:
            sample += [common.movie_id(f) for f in p.get("favorites", [])[:2]]
        for rows in self.generated.values():
            sample += [r["tmdbId"] for r in rows[:2]]
        sample = list(dict.fromkeys(m for m in sample if m))[:30]
        found = [m for m in sample if self._movie_exists(probe_key, m)]
        fixture = [m for m in FIXTURE_PROBE_IDS if self._movie_exists(probe_key, m)]
        status, popular = self.call(probe_key, "GET", "/movies/popular")
        pop_n = len((popular or {}).get("items", [])) if status == 200 else 0
        share = len(found) / len(sample) if sample else 0.0
        self.log(
            f"catalog: {len(found)}/{len(sample)} sampled persona movies found ({share:.0%}); "
            f"fixture probes {len(fixture)}/{len(FIXTURE_PROBE_IDS)}; popular page {pop_n}"
        )
        if share >= 0.8:
            self.log("verdict: the catalog knows the persona movies - ready to seed.")
            return 0
        self.log(
            "verdict: this looks like the ~49-movie FIXTURE catalog (TMDB_ACCESS_TOKEN unset).\n"
            "  Most persona favourites/ratings will 404 and be skipped. Either restart\n"
            "  core-api with TMDB_ACCESS_TOKEN=... (unknown movies are then fetched on demand),\n"
            "  or bulk-fill the catalog first: python3 tools/catalog/movielens_tmdb_ids.py,\n"
            "  then the catalog seeder\n"
            "  with --filmfuse.catalog.seed.id-file=../tools/catalog/out/movielens-tmdb-ids.txt"
        )
        return 3


# --------------------------------------------------------------------------- dry run


def dry_run_plan(
    data: dict, personas: list[dict], generated: dict, net: networks_mod.Network | None, only: str
) -> list[str]:
    lines: list[str] = []
    if only in ("personas", "all"):
        for p in personas:
            planned = planned_ratings(p, generated)
            pub = sum(r["visibility"] == "PUBLIC" for r in planned)
            notes = sum(bool(r["body"]) for r in planned)
            comfort = common.normalise_comfort(p.get("comfort"))
            lines.append(
                f"[{p['key']}] {common.email_for(data, p)} @{p['username']}: "
                f"{len(p.get('genres', {}))} genres, {len(p.get('moods', {}))} moods, "
                f"{len(p.get('favorites', []))} favourites, comfort {json.dumps(comfort)}, "
                f"{'complete onboarding' if p.get('onboarded', True) else 'SKIP onboarding'}, "
                f"{len(planned)} ratings ({sum(r['kind'] == 'anchor' for r in planned)} anchors, "
                f"{pub} public, {notes} with notes)"
            )
    if only in ("network", "all") and net is not None:
        keys = {p["key"] for p in personas}
        for e in net.edges:
            if e.a in keys and e.b in keys:
                close = f" close_by={','.join(e.close_by)}" if e.close_by else ""
                lines.append(f"edge {e.type}: {e.a} -> {e.b}{close}")
        for g in net.groups:
            if all(k in keys for k in [g["creator"], *g["members"]]):
                lines.append(
                    f"group [{g['intent']}] {g['name']}: {g['creator']} + {', '.join(g['members'])}"
                )
    return lines


# --------------------------------------------------------------------------- CLI


def load_generated(path: Path) -> dict:
    if not path.exists():
        print(f"note: {path} not found - seeding anchors only (run generate.py for more ratings)")
        return {}
    return common.load_json(path, "generated ratings")


def main(
    argv: list[str] | None = None,
    transport: Transport = urllib_transport,
    sleeper: Callable[[float], None] = time.sleep,
) -> int:
    ap = argparse.ArgumentParser(
        description="Seed the test personas + fake social networks into the LOCAL stack through "
        "the real APIs. Idempotent. Generated ratings are SYNTHETIC test data.",
        epilog="Seed fast: start core-api with FILMFUSE_RATE_LIMIT_ENABLED=false.",
    )
    ap.add_argument("--personas", type=Path, default=common.DEFAULT_PERSONAS)
    ap.add_argument("--generated", type=Path, default=common.DEFAULT_GENERATED)
    ap.add_argument("--networks", type=Path, default=common.DEFAULT_NETWORKS)
    ap.add_argument("--state", type=Path, default=common.DEFAULT_SEED_STATE)
    ap.add_argument("--api", default=common.DEFAULT_API, help="core-api base (default %(default)s)")
    ap.add_argument(
        "--supabase",
        default=common.DEFAULT_SUPABASE,
        help="Supabase API base (default %(default)s)",
    )
    ap.add_argument("--anon-key", default=None, help="Supabase anon key (default: $ANON_KEY)")
    ap.add_argument("--only", choices=["personas", "network", "all"], default="all")
    ap.add_argument("--keys", help="comma-separated persona keys (default: all)")
    ap.add_argument("--dry-run", action="store_true", help="print the plan; no HTTP")
    ap.add_argument(
        "--preflight",
        action="store_true",
        help="check whether the local catalog knows the persona movies, then exit",
    )
    ap.add_argument("--concurrency", type=int, default=4, help="personas seeded in parallel")
    args = ap.parse_args(argv)

    try:
        data = common.load_personas(args.personas)
        personas = common.select(data, common.parse_keys(args.keys))
        generated = load_generated(args.generated)
        net = None
        if args.only in ("network", "all"):
            net = networks_mod.load(args.networks, data)
    except common.PersonaFileError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except networks_mod.NetworkError as e:
        print("networks.json is invalid:\n  - " + "\n  - ".join(e.errors), file=sys.stderr)
        return 1

    if args.dry_run:
        print(f"DRY RUN (no HTTP): {len(personas)} personas, --only {args.only}")
        for line in dry_run_plan(data, personas, generated, net, args.only):
            print("  " + line)
        return 0

    anon = args.anon_key or os.environ.get("ANON_KEY")
    if not anon:
        print(
            'error: set ANON_KEY (eval "$(supabase status -o env | grep ^ANON_KEY=)")',
            file=sys.stderr,
        )
        return 1
    state = common.load_json(args.state, "seed state") if args.state.exists() else {}
    api = Api(transport, supabase=args.supabase, api=args.api, anon_key=anon, sleeper=sleeper)
    seeder = Seeder(api, data, generated, state, concurrency=args.concurrency)
    try:
        if args.preflight:
            return seeder.preflight(personas)
        if args.only in ("personas", "all"):
            seeder.seed_personas(personas)
        if net is not None:
            keys = {p["key"] for p in personas} if args.keys else None
            seeder.seed_network(net, keys)
    except SeedError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    finally:
        state["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        common.write_json(args.state, state)

    return print_report(seeder, args.state)


def print_report(seeder: Seeder, state_path: Path) -> int:
    reps = list(seeder.reports.values())
    print("\n=== seed report ===")
    errors = 0
    for r in reps:
        line = (
            f"{r.key:28s} {'new ' if r.created else '    '}{r.ratings_written:4d} written "
            f"{r.ratings_unchanged:4d} unchanged {len(r.skipped_movies):4d} skipped (404)"
        )
        print(line)
        if r.fallback_favorites:
            print(f"    !!! fallback favourites: {', '.join(r.fallback_favorites)}")
        for e in r.errors:
            print(f"    ERROR {e}")
        errors += len(r.errors)
    skipped = sum(len(r.skipped_movies) for r in reps)
    if skipped:
        print(f"{skipped} movies were not in the local catalog and were skipped (see --preflight).")
    if seeder.api.rate_limited:
        print(
            f"{seeder.api.rate_limited} requests were rate limited (429) and retried; "
            "start core-api with FILMFUSE_RATE_LIMIT_ENABLED=false to seed faster."
        )
    if seeder.network_log or seeder.network_errors:
        print(f"network: {len(seeder.network_log)} changes, {len(seeder.network_errors)} errors")
        for e in seeder.network_errors:
            print(f"    ERROR {e}")
    errors += len(seeder.network_errors)
    print(f"state -> {state_path}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
