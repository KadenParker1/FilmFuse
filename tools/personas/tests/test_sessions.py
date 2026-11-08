import json

import pytest
from personas_testlib import API, SUPA, FakeStack, sample

import common
import networks
import seed
import sessions


def member(key, genres, comfort=None):
    return sessions.Member(
        key, f"id-{key}", key.replace("-", "_"), genres, common.normalise_comfort(comfort)
    )


MOM = member("mom", {"16": 10, "10751": 8, "27": 1}, {"maxCertification": "PG"})
FAN = member("fan", {"27": 10, "53": 8, "16": 3})
RESULTS = [
    {
        "rank": 1,
        "movieId": 1,
        "title": "Cartoon",
        "year": 2000,
        "certification": "G",
        "genreIds": [16, 10751],
    },
    {
        "rank": 2,
        "movieId": 2,
        "title": "Slasher",
        "year": 2001,
        "certification": "R",
        "genreIds": [27, 53],
    },
    {
        "rank": 3,
        "movieId": 3,
        "title": "Mystery Box",
        "year": None,
        "certification": None,
        "genreIds": [],
    },
]


def test_diagnostics_maths():
    d = sessions.diagnose(RESULTS, [MOM, FAN])
    r1, r2, r3 = d["rows"]
    # mom: (10 + 8) / 2 = 9; fan: (3 + 5 unscored) / 2 = 4
    assert r1["affinity"] == {"mom": 9.0, "fan": 4.0}
    assert (r1["min"], r1["mean"], r1["spread"]) == (4.0, 6.5, 5.0)
    # mom: (1 + 5) / 2 = 3; fan: (10 + 8) / 2 = 9
    assert r2["affinity"] == {"mom": 3.0, "fan": 9.0}
    assert r3["affinity"] == {"mom": 5.0, "fan": 5.0} and r3["spread"] == 0
    assert d["members"]["mom"]["enjoyShare"] == pytest.approx(1 / 3, abs=1e-3)
    assert d["members"]["fan"]["enjoyShare"] == pytest.approx(1 / 3, abs=1e-3)
    assert d["members"]["mom"]["meanAffinity"] == pytest.approx((9 + 3 + 5) / 3, abs=0.01)
    s = d["summary"]
    assert s["results"] == 3
    assert s["meanOfMins"] == pytest.approx((4 + 3 + 5) / 3, abs=0.01)
    assert s["meanSpread"] == pytest.approx((5 + 6 + 0) / 3, abs=0.01)
    assert s["worstMemberEnjoyShare"] == pytest.approx(0.333, abs=1e-3)


def test_comfort_violations_flagged_as_bugs():
    d = sessions.diagnose(RESULTS, [MOM, FAN])
    assert d["summary"]["comfortViolations"] == 2
    reasons = [v["reason"] for v in d["violations"]]
    assert any("R exceeds mom's cap PG" in r for r in reasons)
    assert any("unrated movie" in r for r in reasons)
    assert d["rows"][0]["violations"] == []


@pytest.mark.parametrize(
    "comfort,cert,bad",
    [
        (None, "NC_17", False),
        ({"maxCertification": "PG_13"}, "PG_13", False),
        ({"maxCertification": "PG_13"}, "R", True),
        ({"maxCertification": "G"}, "PG", True),
        ({"maxCertification": "PG", "allowUnrated": True}, None, False),
        ({"maxCertification": "PG"}, None, True),
        ({"contentLimits": {"FRIGHTENING": "NONE"}}, None, False),  # no cert cap: can't check here
    ],
)
def test_comfort_violation_rules(comfort, cert, bad):
    assert bool(sessions.comfort_violation(member("m", {}, comfort), cert)) is bad


def test_empty_results():
    d = sessions.diagnose([], [MOM])
    assert d["summary"]["results"] == 0 and d["summary"]["meanOfMeans"] is None
    assert d["members"]["mom"]["enjoyShare"] is None


def test_render_markdown():
    report = {
        "generatedAt": "now",
        "api": "x",
        "strategies": ["mean"],
        "sessions": [
            {
                "group": "G",
                "intent": "hard",
                "strategy": "mean",
                "note": "n",
                "sessionId": "s1",
                "effectiveFilter": "PG",
                "diagnostics": sessions.diagnose(RESULTS, [MOM, FAN]),
            },
            {"group": "H", "intent": "easy", "strategy": "mean", "error": "rate limited"},
        ],
    }
    md = sessions.render_markdown(report)
    assert md.startswith("# Group session report")
    assert "Diagnostic, not ground truth" in md
    assert "## BUG: comfort violations" in md
    assert "| G | hard | mean | 3 |" in md
    assert "| H | easy | mean | rate limited |" in md
    assert "| 2 | Slasher (2001) **BUG** | R | Horror, Thriller |" in md
    assert "| 3 | Mystery Box **BUG** | unrated | - |" in md


def test_parse_strategies():
    assert sessions.parse_strategies("mean") == ["mean"]
    assert sessions.parse_strategies("all") == sessions.STRATEGIES
    with pytest.raises(ValueError):
        sessions.parse_strategies("median")


NET = {
    "clusters": [{"name": "Fam", "members": ["disney-mom", "horror-fanatic", "mcu-teen"]}],
    "groups": [
        {"name": "Chaos", "creator": "disney-mom", "members": ["horror-fanatic"], "intent": "pair"},
        {"name": "Teen", "creator": "mcu-teen", "members": ["disney-mom"], "intent": "pair"},
    ],
}


@pytest.fixture
def seeded(tmp_path):
    data = sample()
    stack = FakeStack()
    stack.add_movies(*[m for p in data["personas"] for m in common.reserved_ids(p)])
    stack.catalog[50] = {"id": 50, "title": "Scary", "releaseYear": 2010, "genreIds": []}
    api = seed.Api(stack, supabase=SUPA, api=API, anon_key="a", log=lambda m: None)
    s = seed.Seeder(api, data, {}, {}, log=lambda m: None)
    s.seed_personas(data["personas"])
    net = networks.build(NET, [p["key"] for p in data["personas"]])
    s.seed_network(net)
    assert not s.network_errors
    return data, stack, s.state, net


def runner_for(stack, data, state, **kw):
    api = seed.Api(stack, supabase=SUPA, api=API, anon_key="a", log=lambda m: None)
    return sessions.SessionRunner(
        seed.Seeder(api, data, {}, state, log=lambda m: None), log=lambda m: None, **kw
    )


def test_runner_fetches_missing_genres_and_reports(seeded):
    data, stack, state, net = seeded
    stack.catalog[50]["genreIds"] = [27]  # details know the genre, the session result doesn't
    stack.session_results = [
        {
            "rank": 1,
            "certification": "R",
            "groupScore": 0.5,
            "movie": {"id": 50, "title": "Scary", "releaseYear": 2010, "genreIds": []},
        }
    ]
    out = runner_for(stack, data, state).run(net, ["Chaos"], ["mean", "least_misery"])
    assert [o["strategy"] for o in out] == ["mean", "least_misery"]
    rows = out[0]["diagnostics"]["rows"]
    assert rows[0]["genreIds"] == [27]
    assert rows[0]["affinity"] == {"disney-mom": 1.0, "horror-fanatic": 10.0}
    assert out[0]["diagnostics"]["summary"]["comfortViolations"] == 1  # R vs disney-mom's PG
    gets = [p for m, p, _ in stack.calls if m == "GET" and p == "/api/v1/movies/50"]
    assert len(gets) == 1  # cached across strategies


def test_runner_sends_enum_strategy(seeded, monkeypatch):
    data, stack, state, net = seeded
    sent = []
    orig = stack.route

    def spy(method, path, q, body, uid):
        if path == "/api/v1/sessions":
            sent.append(body)
        return orig(method, path, q, body, uid)

    monkeypatch.setattr(stack, "route", spy)
    runner_for(stack, data, state).run(net, ["Chaos"], ["closeness_weighted"])
    assert sent == [{"groupId": state["groups"]["Chaos"]["id"], "strategy": "CLOSENESS_WEIGHTED"}]


def test_runner_429_skips_rest_for_that_creator(seeded):
    data, stack, state, net = seeded
    stack.inject.append(("POST", "/api/v1/sessions", 429, {"retry-after": "1800"}))
    out = runner_for(stack, data, state).run(net, None, ["mean", "fairness"])
    by = {(o["group"], o["strategy"]): o for o in out}
    assert "rate limited" in by[("Chaos", "mean")]["error"]
    assert "retry after 1800s" in by[("Chaos", "mean")]["error"]
    assert "rate limited" in by[("Chaos", "fairness")]["error"]  # not attempted
    assert "error" not in by[("Teen", "mean")]  # other creator unaffected
    posts = [c for c in stack.calls if c[0] == "POST" and c[1] == "/api/v1/sessions"]
    assert len(posts) == 3


def test_runner_429_waits_when_allowed(seeded):
    data, stack, state, net = seeded
    stack.inject.append(("POST", "/api/v1/sessions", 429, {"retry-after": "5"}))
    sleeps = []
    out = runner_for(stack, data, state, wait_on_limit=True, sleeper=sleeps.append).run(
        net, ["Chaos"], ["mean"]
    )
    assert sleeps == [5.0] and "error" not in out[0]


def test_runner_unseeded_group(seeded):
    data, stack, state, net = seeded
    del state["groups"]["Teen"]
    out = runner_for(stack, data, state).run(net, ["Teen"], ["mean"])
    assert out[0]["error"].startswith("not seeded")


def test_main_writes_report(seeded, tmp_path):
    data, stack, state, _ = seeded
    files = {}
    for name, obj in (("p", data), ("n", NET), ("s", state)):
        files[name] = tmp_path / f"{name}.json"
        files[name].write_text(json.dumps(obj))
    stack.session_results = [
        {
            "rank": 1,
            "certification": "G",
            "movie": {"id": 862, "title": "Toy Story", "releaseYear": 1995, "genreIds": [16]},
        }
    ]
    out = tmp_path / "r" / "report.md"
    code = sessions.main(
        [
            "--personas",
            str(files["p"]),
            "--networks",
            str(files["n"]),
            "--state",
            str(files["s"]),
            "--out",
            str(out),
            "--anon-key",
            "a",
            "--api",
            API,
            "--supabase",
            SUPA,
        ],
        transport=stack,
    )
    assert code == 0
    assert "not ground truth" in out.read_text()
    rep = json.loads(out.with_suffix(".json").read_text())
    assert len(rep["sessions"]) == 2 and rep["strategies"] == ["mean"]
