"""End-to-end smoke test: real local Supabase (auth/storage) + core-api (:8090) + ML service (:8001).

Usage: ANON_KEY=... python3 smoke.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

SUPA = "http://127.0.0.1:55321"
API = "http://127.0.0.1:8090/api/v1"
ANON = os.environ["ANON_KEY"]
results = []


def http(method, url, body=None, token=None, headers=None, raw=None):
    h = {"Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = f"Bearer {token}"
    h.update(headers or {})
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw_body = r.read()
            ctype = r.headers.get("Content-Type") or ""
            if "json" in ctype:
                return r.status, json.loads(raw_body.decode() or "null")
            return r.status, raw_body if ctype.startswith("image/") else raw_body.decode(errors="replace")
    except urllib.error.HTTPError as e:
        txt = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {detail}"))


def signup(tag):
    email = f"smoke-{tag}-{uuid.uuid4().hex[:8]}@example.com"
    s, b = http("POST", f"{SUPA}/auth/v1/signup", {"email": email, "password": "correct-horse-9"},
                headers={"apikey": ANON})
    check(f"supabase signup {tag}", s == 200 and b.get("access_token"), (s, b))
    return b["access_token"], b["user"]["id"]


ta, ida = signup("a")
tb, idb = signup("b")

s, me = http("GET", f"{API}/me", token=ta)
check("GET /me provisions with real ES256 JWT", s == 200 and me["id"] == ida, (s, me))
s, _ = http("GET", f"{API}/me", token="not-a-jwt")
check("bad token -> 401", s == 401, s)

for tok, name in ((ta, "smokea"), (tb, "smokeb")):
    uname = name + uuid.uuid4().hex[:6]
    s, b = http("PATCH", f"{API}/me", {"username": uname, "displayName": name.title()}, token=tok)
    check(f"PATCH /me username {name}", s == 200 and b["username"] == uname, (s, b))

# Onboarding prefs + complete for A (builds a vector through the real ML service)
genres = [{"genreId": g, "score": sc} for g, sc in [(878, 9), (28, 8), (18, 8), (35, 3), (27, 2)]]
s, _ = http("PUT", f"{API}/me/preferences/genres", genres, token=ta)
check("PUT genre prefs", s == 200, s)
s, pop = http("GET", f"{API}/movies/popular", token=ta)
ids = [m["id"] for m in pop["items"]]
s, _ = http("PUT", f"{API}/me/preferences/favorites", [{"movieId": m, "rank": i + 1} for i, m in enumerate(ids[:3])], token=ta)
check("PUT favorites", s == 200, s)
s, b = http("POST", f"{API}/me/onboarding/complete", token=ta)
check("onboarding complete", s == 200 and b["onboardingCompletedAt"], (s, b))
for _ in range(30):
    s, tp = http("GET", f"{API}/me/taste-profile", token=ta)
    if tp.get("status") == "FRESH":
        break
    time.sleep(1)
check("vector built via ML service (FRESH)", tp.get("status") == "FRESH", tp)
check("vector model is the ML placeholder (not core-api fallback)", tp.get("modelVersion") == "placeholder-0", tp.get("modelVersion"))

# Friends
s, b = http("POST", f"{API}/friends/requests", {"targetUserId": idb}, token=ta)
check("send friend request", s == 200 and b["friendship"] == "REQUEST_SENT", (s, b))
s, b = http("POST", f"{API}/friends/requests/{ida}/accept", token=tb)
check("accept friend request", s == 200 and b["friendship"] == "FRIENDS", (s, b))

# Comfort: B caps at PG-13
s, _ = http("PUT", f"{API}/me/settings/comfort", {"maxCertification": "PG_13", "contentLimits": {}, "allowUnrated": False}, token=tb)
check("B comfort PG-13", s == 200, s)

# Group + session
s, g = http("POST", f"{API}/groups", {"name": "Smoke Night", "memberIds": [idb]}, token=ta)
check("create group", s == 201, (s, g))
s, _ = http("POST", f"{API}/groups/{g['id']}/invites/accept", token=tb)
check("B accepts group invite", s == 200, s)
s, sess = http("POST", f"{API}/sessions", {"groupId": g["id"]}, token=ta)
check("create session (ML group-recommendations)", s == 201 and len(sess["results"]) > 0, (s, sess))
certs = {r["certification"] for r in sess["results"]}
check("no R/NC-17/unrated results for PG-13 member", not (certs & {"R", "NC_17", None}), certs)
check("effective filter label mentions PG-13", "PG-13" in sess["effectiveFilter"]["label"], sess["effectiveFilter"])
check("member vector flag: A has vector", any(m["hasVector"] for m in sess["members"] if m["user"]["id"] == ida), sess["members"])
mid = sess["results"][0]["movie"]["id"]
s, b = http("PUT", f"{API}/sessions/{sess['id']}/votes/{mid}", {"vote": "UP"}, token=tb)
check("B votes", s == 200, s)
SESSION_ID = sess["id"]

# Ratings + feed
s, b = http("PUT", f"{API}/ratings/{mid}", {"score": 9, "body": "Smoke test loved it", "visibility": "FRIENDS"}, token=ta)
check("A rates a movie", s == 200, (s, b))
s, feed = http("GET", f"{API}/feed?scope=FRIENDS", token=tb)
check("A's rating appears in B's FRIENDS feed", any(i["movie"]["id"] == mid and i["author"]["id"] == ida for i in feed["items"]), feed)
s, feed = http("GET", f"{API}/feed?scope=EVERYONE", token=tb)
check("FRIENDS rating not in EVERYONE feed", not any(i["author"]["id"] == ida for i in feed["items"]), feed)

# For you
s, fy = http("GET", f"{API}/me/recommendations", token=ta)
check("For you returns items", s == 200 and len(fy["items"]) > 0, (s, fy))
check("For you excludes rated movie", all(i["movie"]["id"] != mid for i in fy["items"]), fy)

# Profile photo via real Storage signed upload
s, up = http("POST", f"{API}/me/profile-photo/upload-url", token=ta)
check("signed upload URL", s == 200 and up["uploadUrl"].startswith(SUPA), (s, up))
jpeg = bytes.fromhex("ffd8ffe000104a46494600010100000100010000ffdb004300") + b"\x08" * 64 + bytes.fromhex("ffd9")
s, b = http("PUT", up["uploadUrl"], raw=jpeg, headers={"Content-Type": "image/jpeg"})
check("PUT bytes to Supabase Storage", s == 200, (s, b))
s, prof = http("POST", f"{API}/me/profile-photo", {"path": up["path"]}, token=ta)
check("confirm photo", s == 200 and prof["profileImageUrl"], (s, prof))
s, _ = http("GET", prof["profileImageUrl"])
check("public photo URL serves", s == 200, s)

# Account deletion (real auth admin API)
s, _ = http("DELETE", f"{API}/me", token=tb)
check("DELETE /me", s == 204, s)
s, _ = http("GET", f"{API}/me", token=tb)
check("deleted account's token -> 401", s == 401, s)
s, b = http("POST", f"{SUPA}/auth/v1/token?grant_type=password", {"email": "x", "password": "y"}, headers={"apikey": ANON})

print(json.dumps({"session_id": SESSION_ID, "user_a": ida, "user_b": idb}))
failed = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
