"""End-to-end smoke test: real local Supabase (auth/storage) + core-api (:8090) + ML service (:8001).

Usage: ANON_KEY=... python3 smoke.py

Creates its own throwaway accounts (A-D). Prints SKIP for a check the local catalog cannot set up.
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

# Undo "Not interested" on For you (204, idempotent; the movie comes back)
fy_id = fy["items"][0]["movie"]["id"]
s, _ = http("POST", f"{API}/me/recommendations/{fy_id}/dismiss", token=ta)
check("dismiss a For you pick", s == 204, s)
s, fy2 = http("GET", f"{API}/me/recommendations", token=ta)
check("dismissed pick leaves For you", s == 200 and all(i["movie"]["id"] != fy_id for i in fy2["items"]), fy2)
s1, _ = http("DELETE", f"{API}/me/recommendations/{fy_id}/dismiss", token=ta)
s2, _ = http("DELETE", f"{API}/me/recommendations/{fy_id}/dismiss", token=ta)
check("undo For you dismiss -> 204, again -> 204", (s1, s2) == (204, 204), (s1, s2))
s, fy3 = http("GET", f"{API}/me/recommendations", token=ta)
check("undone pick is back in For you", s == 200 and any(i["movie"]["id"] == fy_id for i in fy3["items"]), fy3)

# Late joiners: C is A's friend, D is nobody's friend
tc, idc = signup("c")
td, idd = signup("d")
for tok in (tc, td):
    http("GET", f"{API}/me", token=tok)
s, _ = http("POST", f"{API}/friends/requests", {"targetUserId": idc}, token=ta)
s2, _ = http("POST", f"{API}/friends/requests/{ida}/accept", token=tc)
check("A and C become friends", (s, s2) == (200, 200), (s, s2))

s, adhoc = http("POST", f"{API}/sessions", {"memberIds": [idb]}, token=ta)
check("create ad-hoc session A+B", s == 201 and adhoc["groupId"] is None, (s, adhoc))
ADHOC = adhoc["id"]
s, _ = http("GET", f"{API}/sessions/{ADHOC}", token=tc)
check("C (not in ad-hoc session) GET -> 404", s == 404, s)
s, b = http("POST", f"{API}/sessions/{ADHOC}/members", {"userIds": [idd]}, token=ta)
check("adding a non-friend to a session -> 400", s == 400, (s, b))
s, b = http("POST", f"{API}/sessions/{ADHOC}/members", {"userIds": [idc]}, token=tc)
check("non-member can't add people -> 404", s == 404, (s, b))
s, b = http("POST", f"{API}/sessions/{ADHOC}/members", {"userIds": [idc]}, token=ta)
check("A adds friend C to the open session", s == 200 and any(m["user"]["id"] == idc for m in b["members"]), (s, b))
s, c_view = http("GET", f"{API}/sessions/{ADHOC}", token=tc)
check("added C can GET the session", s == 200 and c_view["memberCount"] == 3, (s, c_view))
adhoc_mid = c_view["results"][0]["movie"]["id"]
s, b = http("PUT", f"{API}/sessions/{ADHOC}/votes/{adhoc_mid}", {"vote": "UP"}, token=tc)
check("added C can vote", s == 200, (s, b))
s, _ = http("GET", f"{API}/sessions/{ADHOC}", token=td)
check("outsider D GET ad-hoc session -> 404", s == 404, s)
s, _ = http("POST", f"{API}/sessions/{ADHOC}/join", token=tc)
check("join is idempotent for a member", s == 200, s)
s, _ = http("POST", f"{API}/sessions/{ADHOC}/join", token=td)
check("outsider can't join an ad-hoc session -> 404", s == 404, s)

# End early: creator only
s, b = http("POST", f"{API}/sessions/{ADHOC}/end", token=tb)
check("non-creator member ends session -> 403", s == 403, (s, b))
s, b = http("POST", f"{API}/sessions/{ADHOC}/end", token=td)
check("outsider ends session -> 404", s == 404, (s, b))
s, b = http("POST", f"{API}/sessions/{ADHOC}/end", token=ta)
check("creator ends session early", s == 200 and b["status"] == "EXPIRED" and b["endedEarly"], (s, b))
s, b = http("GET", f"{API}/sessions/{ADHOC}", token=tb)
check("everyone sees it ended early", s == 200 and b["status"] == "EXPIRED" and b["endedEarly"], (s, b))
s, b = http("PUT", f"{API}/sessions/{ADHOC}/votes/{adhoc_mid}", {"vote": "DOWN"}, token=tc)
check("voting after end -> 409", s == 409, (s, b))
s, _ = http("POST", f"{API}/sessions/{ADHOC}/end", token=ta)
check("ending twice -> 409", s == 409, s)
s, live = http("GET", f"{API}/sessions?status=OPEN", token=tb)
check("ended session leaves B's Live now", s == 200 and all(i["id"] != ADHOC for i in live["items"]), live)

# Pending rating undo: a pick lands in C's "Rate what you watched"
s, picked = http("POST", f"{API}/sessions", {"memberIds": [idc]}, token=ta)
check("create ad-hoc session A+C", s == 201 and picked["results"], (s, picked))
pick_mid = picked["results"][0]["movie"]["id"]
s, b = http("POST", f"{API}/sessions/{picked['id']}/pick", {"movieId": pick_mid}, token=ta)
check("pick a movie", s == 200 and b["status"] == "DECIDED", (s, b))
s, pend = http("GET", f"{API}/me/pending-ratings", token=tc)
check("pick shows up in C's pending ratings", s == 200 and any(p["movie"]["id"] == pick_mid for p in pend), pend)
s, _ = http("POST", f"{API}/me/pending-ratings/{pick_mid}/dismiss", token=tc)
s2, pend = http("GET", f"{API}/me/pending-ratings", token=tc)
check("dismissed pending rating disappears", (s, s2) == (204, 200) and all(p["movie"]["id"] != pick_mid for p in pend), (s, pend))
s1, _ = http("DELETE", f"{API}/me/pending-ratings/{pick_mid}/dismiss", token=tc)
s2, _ = http("DELETE", f"{API}/me/pending-ratings/{pick_mid}/dismiss", token=tc)
check("undo pending dismiss -> 204, again -> 204", (s1, s2) == (204, 204), (s1, s2))
s, pend = http("GET", f"{API}/me/pending-ratings", token=tc)
check("undone pending rating is back", s == 200 and any(p["movie"]["id"] == pick_mid for p in pend), pend)

# Group member joins an open group session late
s, _ = http("POST", f"{API}/groups/{g['id']}/members", {"userIds": [idc]}, token=ta)
s2, _ = http("POST", f"{API}/groups/{g['id']}/invites/accept", token=tc)
check("C joins the group after the session started", (s, s2) == (200, 200), (s, s2))
s, b = http("GET", f"{API}/sessions/{SESSION_ID}", token=tc)
check("group member not in session GET -> 403 'join'", s == 403 and "join" in str(b.get("message", "")).lower(), (s, b))
s, _ = http("GET", f"{API}/sessions/{SESSION_ID}", token=td)
check("outsider GET group session -> 404", s == 404, s)
s, _ = http("POST", f"{API}/sessions/{SESSION_ID}/join", token=td)
check("outsider join group session -> 404", s == 404, s)

# Comfort tightening: C caps at G before joining, so non-G picks disappear for everyone
s, before = http("GET", f"{API}/sessions/{SESSION_ID}", token=ta)
over_limit = [r["movie"]["id"] for r in before["results"] if r["certification"] != "G"]
tighten = s == 200 and before["hiddenResultCount"] == 0 and bool(over_limit)
if tighten:
    s, _ = http("PUT", f"{API}/me/settings/comfort", {"maxCertification": "G", "contentLimits": {}, "allowUnrated": False}, token=tc)
    check("C comfort G", s == 200, s)
else:
    print(f"SKIP comfort tightening: every visible result is already G ({before.get('hiddenResultCount')} hidden)")
s, b = http("POST", f"{API}/sessions/{SESSION_ID}/join", token=tc)
check("active group member joins the open session", s == 200 and any(m["user"]["id"] == idc for m in b["members"]), (s, b))
s, b = http("GET", f"{API}/sessions/{SESSION_ID}", token=tc)
check("joined C can GET the session", s == 200, (s, b))
visible = [r for r in b["results"] if r["votes"]["vetoed"] is False] if s == 200 else []
if visible:
    s, v = http("PUT", f"{API}/sessions/{SESSION_ID}/votes/{visible[0]['movie']['id']}", {"vote": "UP"}, token=tc)
    check("joined C can vote", s == 200, (s, v))
if tighten:
    for tok, who in ((ta, "A"), (tb, "B"), (tc, "C")):
        s, view = http("GET", f"{API}/sessions/{SESSION_ID}", token=tok)
        check(f"stricter late joiner hides over-limit picks for {who}",
              s == 200 and view["hiddenResultCount"] == len(over_limit)
              and all(r["certification"] == "G" for r in view["results"])
              and not any(r["movie"]["id"] in over_limit for r in view["results"]), (s, view))
    view_ok = s == 200
    s, v = http("PUT", f"{API}/sessions/{SESSION_ID}/votes/{over_limit[0]}", {"vote": "UP"}, token=ta)
    check("hidden pick can't be voted on -> 404", s == 404, (s, v))
    check("effective filter is now G only", view_ok and view["effectiveFilter"]["maxCertification"] == "G" and view["effectiveFilter"]["label"].startswith("Rated G only"), view["effectiveFilter"] if view_ok else view)

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
