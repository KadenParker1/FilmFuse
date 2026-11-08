import json

import pytest
from personas_testlib import API, SUPA, FakeStack, sample

import common
import networks
import seed

GENERATED = {
    "disney-mom": [
        {"tmdbId": 9001, "title": "Gen A", "year": 2001, "score": 7},
        {"tmdbId": 9002, "title": "Gen B", "year": 2002, "score": 4},
    ],
    "horror-fanatic": [{"tmdbId": 9003, "title": "Gen C", "year": 2003, "score": 9}],
}
NET = {
    "clusters": [
        {
            "name": "Fam",
            "members": ["disney-mom", "mcu-teen"],
            "close": [["disney-mom", "mcu-teen"]],
        }
    ],
    "edges": [
        {"a": "disney-mom", "b": "horror-fanatic", "type": "friend", "close": "b"},
        {"a": "film-bro", "b": "disney-mom", "type": "pending"},
        {"a": "horror-fanatic", "b": "hallmark-fan", "type": "block"},
    ],
    "groups": [
        {
            "name": "Chaos",
            "emoji": "x",
            "creator": "disney-mom",
            "members": ["horror-fanatic", "mcu-teen"],
            "intent": "hard",
        }
    ],
}


def all_ids(data, generated):
    ids = set()
    for p in data["personas"]:
        ids |= common.reserved_ids(p)
    for rows in generated.values():
        ids |= {r["tmdbId"] for r in rows}
    return ids


@pytest.fixture
def data():
    return sample()


@pytest.fixture
def stack(data):
    s = FakeStack()
    s.add_movies(*all_ids(data, GENERATED))
    return s


def make(stack, data, generated=GENERATED, state=None, sleeps=None):
    api = seed.Api(
        stack,
        supabase=SUPA,
        api=API,
        anon_key="anon",
        sleeper=(sleeps.append if sleeps is not None else lambda s: None),
        log=lambda m: None,
    )
    return seed.Seeder(
        api, data, generated, state if state is not None else {}, concurrency=2, log=lambda m: None
    )


def persona(data, key):
    return common.personas_by_key(data)[key]


def test_full_call_sequence_for_one_persona(stack, data):
    s = make(stack, data)
    rep = s.seed_persona(persona(data, "disney-mom"))
    assert rep.errors == [] and rep.created
    calls = [(m, p.split("?")[0]) for m, p in stack.calls_for("disney-mom@filmfuse.test")]
    expected = [
        ("POST", "/auth/v1/signup"),
        ("GET", "/api/v1/me"),
        ("PATCH", "/api/v1/me"),
        ("PUT", "/api/v1/me/preferences/genres"),
        ("PUT", "/api/v1/me/preferences/moods"),
        *[
            ("GET", f"/api/v1/movies/{f['tmdbId']}")
            for f in persona(data, "disney-mom")["favorites"]
        ],
        ("PUT", "/api/v1/me/preferences/favorites"),
        ("PUT", "/api/v1/me/settings/comfort"),
        ("POST", "/api/v1/me/onboarding/complete"),
        ("GET", "/api/v1/me/ratings"),
    ]
    assert calls[: len(expected)] == expected
    rating_puts = [p for m, p in calls[len(expected) :] if m == "PUT"]
    assert len(rating_puts) == 3 + 2  # anchors + generated
    uid = rep.user_id
    assert stack.profiles[uid]["username"] == "magic_kingdom_meg"
    assert stack.prefs[uid]["/me/preferences/genres"][0] == {"genreId": 12, "score": 7}
    assert {"moodTag": "cozy", "score": 9} in stack.prefs[uid]["/me/preferences/moods"]
    assert [f["rank"] for f in stack.prefs[uid]["/me/preferences/favorites"]] == [1, 2, 3, 4]
    r = stack.ratings[uid][9001]
    assert r["source"] == "QUICK_RATE" and r["score"] == 7
    assert r["visibility"] in ("PUBLIC", "FRIENDS", "PRIVATE")
    assert s.state["personas"]["disney-mom"]["userId"] == uid


def test_idempotent_rerun(stack, data):
    state = {}
    first = make(stack, data, state=state)
    first.seed_personas(data["personas"])
    first.seed_network(networks.build(NET, [p["key"] for p in data["personas"]]))
    users, friends, groups = dict(stack.users), set(stack.friends), dict(stack.groups)
    n_calls = len(stack.calls)

    second = make(stack, data, state=json.loads(json.dumps(state)))
    second.seed_personas(data["personas"])
    second.seed_network(networks.build(NET, [p["key"] for p in data["personas"]]))
    new_calls = stack.calls[n_calls:]
    assert (
        stack.users == users and stack.friends == friends and stack.groups.keys() == groups.keys()
    )
    assert all(not r.created for r in second.reports.values())
    assert ("POST", "/auth/v1/signup", "disney-mom@filmfuse.test") not in new_calls
    assert any(m == "POST" and p.startswith("/auth/v1/token") for m, p, _ in new_calls)
    assert not [c for c in new_calls if c[0] == "POST" and c[1] == "/api/v1/friends/requests"]
    assert not [c for c in new_calls if c[0] == "PUT" and c[1].startswith("/api/v1/ratings/")]
    assert not [c for c in new_calls if c[0] == "POST" and c[1] == "/api/v1/groups"]
    assert sum(r.ratings_unchanged for r in second.reports.values()) > 0
    assert second.network_errors == []


def test_existing_account_without_state_signs_in(stack, data):
    make(stack, data).seed_persona(persona(data, "film-bro"))
    s = make(stack, data)  # no state: signup first, 422, then sign in
    rep = s.seed_persona(persona(data, "film-bro"))
    assert not rep.created
    auth = [p for m, p in stack.calls_for("film-bro@filmfuse.test") if p.startswith("/auth")]
    assert auth[-2:] == ["/auth/v1/signup", "/auth/v1/token?grant_type=password"]


def test_wrong_password_is_reported(stack, data):
    stack.users["film-bro@filmfuse.test"] = {"id": "x", "password": "other"}
    with pytest.raises(seed.SeedError, match="password"):
        seed.ensure_account(
            make(stack, data).api, "film-bro", "film-bro@filmfuse.test", "pw", False
        )


def test_404_movie_skipped_and_counted(stack, data):
    del stack.catalog[9002]
    del stack.catalog[493922]  # an anchor (Hereditary)
    rep = make(stack, data).seed_persona(persona(data, "disney-mom"))
    assert rep.errors == []
    assert len(rep.skipped_movies) == 2
    assert any("Gen B" in m for m in rep.skipped_movies)
    assert any("Hereditary" in m for m in rep.skipped_movies)
    assert rep.ratings_written == 3


def test_favourite_fallback(data):
    stack = FakeStack()
    stack.add_movies(862)  # only Toy Story of disney-mom's favourites
    stack.catalog[5000] = {"id": 5000, "title": "Moana", "releaseYear": 2016, "genreIds": [16]}
    stack.add_movies(6000, 6001, title="Popular")
    rep = make(stack, data, generated={}).seed_persona(persona(data, "disney-mom"))
    favs = stack.prefs[rep.user_id]["/me/preferences/favorites"]
    assert [f["movieId"] for f in favs] == [862, 5000, 6000]
    assert rep.fallback_favorites == ["Moana [5000] (search)", "Popular 6000 [6000] (popular)"]
    assert sum("favourite" in m for m in rep.skipped_movies) == 3


def test_no_fallback_when_enough_favourites(stack, data):
    del stack.catalog[277834]  # Moana; 3 of 4 favourites remain
    rep = make(stack, data).seed_persona(persona(data, "disney-mom"))
    assert rep.fallback_favorites == []
    assert len(stack.prefs[rep.user_id]["/me/preferences/favorites"]) == 3


def test_429_honours_retry_after(stack, data):
    stack.inject.append(("PUT", "/api/v1/me/preferences/genres", 429, {"retry-after": "7"}))
    stack.inject.append(("POST", "/auth/v1/signup", 429, {}))  # Supabase: no Retry-After
    sleeps = []
    s = make(stack, data, sleeps=sleeps)
    rep = s.seed_persona(persona(data, "disney-mom"))
    assert rep.errors == []
    assert sleeps == [seed.DEFAULT_RETRY_WAIT, 7.0]
    assert s.api.rate_limited == 2
    puts = [p for m, p in stack.calls_for("disney-mom@filmfuse.test") if p.endswith("/genres")]
    assert len(puts) == 2


def test_429_gives_up_after_max_retries(stack, data):
    for _ in range(3):
        stack.inject.append(("GET", "/api/v1/me", 429, {"retry-after": "1"}))
    s = make(stack, data)
    s.api.max_retries = 2
    rep = s._seed_one_safe(persona(data, "disney-mom"))
    assert any("429" in e for e in rep.errors)


def test_onboarded_false_skips_complete(stack, data):
    rep = make(stack, data).seed_persona(persona(data, "mcu-teen"))
    paths = [p for _, p in stack.calls_for("mcu-teen@filmfuse.test")]
    assert "/api/v1/me/onboarding/complete" not in paths
    assert stack.profiles[rep.user_id]["onboardingCompleted"] is False
    assert "/api/v1/me/preferences/genres" in paths  # prefs are still stated


def test_comfort_payload_enums(stack, data):
    s = make(stack, data)
    teen = s.seed_persona(persona(data, "mcu-teen"))
    carol = s.seed_persona(persona(data, "hallmark-fan"))
    film = s.seed_persona(persona(data, "film-bro"))
    assert stack.prefs[teen.user_id]["/me/settings/comfort"] == {
        "maxCertification": "PG_13",
        "contentLimits": {},
        "allowUnrated": True,
    }
    assert stack.prefs[carol.user_id]["/me/settings/comfort"] == {
        "maxCertification": "G",
        "contentLimits": {"LANGUAGE": "MILD", "SEXUAL": "NONE"},
        "allowUnrated": False,
    }
    assert stack.prefs[film.user_id]["/me/settings/comfort"] == {
        "maxCertification": None,
        "contentLimits": {},
        "allowUnrated": False,
    }


@pytest.mark.parametrize("raw,want", [("PG-13", "PG_13"), ("nc-17", "NC_17"), ("g", "G")])
def test_normalise_comfort_spellings(raw, want):
    got = common.normalise_comfort({"maxCertification": raw, "contentLimits": {"violence": "mild"}})
    assert got["maxCertification"] == want and got["contentLimits"] == {"VIOLENCE": "MILD"}


@pytest.mark.parametrize(
    "bad",
    [
        {"maxCertification": "X"},
        {"contentLimits": {"GORE": "MILD"}},
        {"contentLimits": {"VIOLENCE": "LOTS"}},
    ],
)
def test_normalise_comfort_rejects(bad):
    with pytest.raises(ValueError):
        common.normalise_comfort(bad)


def test_planned_ratings_visibility_mix_and_notes():
    p = {"key": "k", "tags": ["horror"], "anchorRatings": [], "favorites": []}
    gen = {"k": [{"tmdbId": i, "title": "t", "year": 2000, "score": 9} for i in range(1, 1001)]}
    rows = seed.planned_ratings(p, gen)
    share = sum(r["visibility"] == "PUBLIC" for r in rows) / len(rows)
    assert 0.15 < share < 0.25
    notes = [r for r in rows if r["body"]]
    assert notes and all(r["visibility"] == "PUBLIC" for r in notes)
    assert all(r["body"] in seed.NOTES["horror"]["high"] for r in notes)
    assert rows == seed.planned_ratings(p, gen)  # deterministic


def test_planned_ratings_anchors_first_and_deduped():
    p = {"key": "k", "anchorRatings": [{"title": "A", "year": 1, "score": 3, "tmdbId": 1}]}
    gen = {
        "k": [
            {"tmdbId": 1, "title": "A", "year": 1, "score": 9},
            {"tmdbId": 2, "title": "B", "year": 2, "score": 5},
        ]
    }
    rows = seed.planned_ratings(p, gen)
    assert [(r["movieId"], r["score"], r["kind"]) for r in rows] == [
        (1, 3, "anchor"),
        (2, 5, "generated"),
    ]


def test_network_seeding(stack, data):
    s = make(stack, data)
    s.seed_personas(data["personas"])
    s.seed_network(networks.build(NET, [p["key"] for p in data["personas"]]))
    assert s.network_errors == []
    uid = {k: v["userId"] for k, v in s.state["personas"].items()}
    assert frozenset((uid["disney-mom"], uid["mcu-teen"])) in stack.friends
    assert frozenset((uid["disney-mom"], uid["horror-fanatic"])) in stack.friends
    assert (uid["disney-mom"], uid["mcu-teen"]) in stack.close
    assert (uid["mcu-teen"], uid["disney-mom"]) in stack.close
    assert (uid["horror-fanatic"], uid["disney-mom"]) in stack.close
    assert (uid["disney-mom"], uid["horror-fanatic"]) not in stack.close
    assert (uid["film-bro"], uid["disney-mom"]) in stack.pending
    assert (uid["horror-fanatic"], uid["hallmark-fan"]) in stack.blocks
    gid = s.state["groups"]["Chaos"]["id"]
    assert set(stack.groups[gid]["members"].values()) == {"ACTIVE"}
    assert len(stack.groups[gid]["members"]) == 3


def test_network_tolerates_409(stack, data):
    s = make(stack, data)
    s.seed_personas(data["personas"])
    uid = {k: v["userId"] for k, v in s.state["personas"].items()}
    stack.blocks.add((uid["disney-mom"], uid["mcu-teen"]))  # out-of-band block
    s.seed_network(networks.build(NET, [p["key"] for p in data["personas"]]))
    assert any("409" in e and "block" in e for e in s.network_errors)


def test_network_only_requires_existing_accounts(stack, data):
    s = make(stack, data)
    with pytest.raises(seed.SeedError, match="seed the personas first"):
        s.seed_network(networks.build(NET, [p["key"] for p in data["personas"]]))


def test_network_subset_keys(stack, data):
    s = make(stack, data)
    s.seed_personas(data["personas"])
    s.seed_network(
        networks.build(NET, [p["key"] for p in data["personas"]]), keys={"disney-mom", "mcu-teen"}
    )
    assert len(stack.friends) == 1 and not stack.groups and not stack.blocks


def write_inputs(tmp_path, data):
    p = tmp_path / "p.json"
    p.write_text(json.dumps(data))
    g = tmp_path / "g.json"
    g.write_text(json.dumps(GENERATED))
    n = tmp_path / "n.json"
    n.write_text(json.dumps(NET))
    return [
        "--personas",
        str(p),
        "--generated",
        str(g),
        "--networks",
        str(n),
        "--state",
        str(tmp_path / "state.json"),
    ]


def test_dry_run_makes_no_calls(tmp_path, data, capsys, monkeypatch):
    monkeypatch.delenv("ANON_KEY", raising=False)
    stack = FakeStack()
    assert seed.main([*write_inputs(tmp_path, data), "--dry-run"], transport=stack) == 0
    assert stack.calls == []
    out = capsys.readouterr().out
    assert "DRY RUN" in out and "SKIP onboarding" in out and "group [hard] Chaos" in out
    assert not (tmp_path / "state.json").exists()


def test_main_end_to_end_with_fake_stack(tmp_path, data, stack, capsys):
    args = [*write_inputs(tmp_path, data), "--anon-key", "anon", "--api", API, "--supabase", SUPA]
    assert seed.main(args, transport=stack, sleeper=lambda s: None) == 0
    state = json.loads((tmp_path / "state.json").read_text())
    assert set(state["personas"]) == {p["key"] for p in data["personas"]}
    assert "Chaos" in state["groups"]
    assert "seed report" in capsys.readouterr().out
    assert seed.main(args, transport=stack, sleeper=lambda s: None) == 0  # re-run


def test_main_requires_anon_key(tmp_path, data, monkeypatch, capsys):
    monkeypatch.delenv("ANON_KEY", raising=False)
    assert seed.main(write_inputs(tmp_path, data), transport=FakeStack()) == 1
    assert "ANON_KEY" in capsys.readouterr().err


def test_preflight_detects_fixture_catalog(tmp_path, data):
    stack = FakeStack()
    stack.add_movies(*seed.FIXTURE_PROBE_IDS)
    s = make(stack, data)
    assert s.preflight(data["personas"]) == 3
    full = FakeStack()
    full.add_movies(*all_ids(data, GENERATED))
    assert make(full, data).preflight(data["personas"]) == 0
