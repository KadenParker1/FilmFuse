"""Tests for tools/personas/resolve.py and the real personas.json.

Run: python-ml-service/.venv/bin/pytest -q tools/personas/tests/test_resolve.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

PERSONAS_DIR = Path(__file__).resolve().parent.parent
if str(PERSONAS_DIR) not in sys.path:
    sys.path.insert(0, str(PERSONAS_DIR))

import resolve as r  # noqa: E402

REAL_PERSONAS = PERSONAS_DIR / "personas.json"
REAL_OVERRIDES = PERSONAS_DIR / "tmdb-overrides.json"
MOVIELENS_DIR = r.DEFAULT_MOVIELENS
needs_movielens = pytest.mark.skipif(
    not (MOVIELENS_DIR / "movies.csv").is_file(),
    reason=f"MovieLens ml-latest-small not found at {MOVIELENS_DIR}",
)


# --------------------------------------------------------------------------- fixtures

MOVIES = [
    (1, "Toy Story (1995)"),
    (2, "Godfather, The (1972)"),
    (3, "Amélie (Fabuleux destin d'Amélie Poulain, Le) (2001)"),
    (4, "Seven (a.k.a. Se7en) (1995)"),
    (5, "Hate (Haine, La) (1995)"),
    (6, "Emma (1996)"),
    (7, "Emma (1996)"),
    (8, "Room, The (2003)"),
    (9, "Army of Darkness (1993)"),
    (10, "Halloween (1978)"),
    (11, "Halloween (2007)"),
    (12, "Birdman: Or (The Unexpected Virtue of Ignorance) (2014)"),
    (13, "No Link Movie (2000)"),
    (14, "Twin (2010)"),
    (15, "Twin (2012)"),
    (16, "Blind Side, The  (2009)"),
    (17, "Moonlight"),
]
LINKS = {i: 1000 + i for i, _ in MOVIES}
LINKS[13] = None


@pytest.fixture
def index() -> r.MovieIndex:
    return r.MovieIndex.from_rows(MOVIES, LINKS)


def persona(**over) -> dict:
    p = {
        "key": "test-person",
        "username": "test_person",
        "displayName": "Test Person",
        "archetype": "Tester",
        "bio": "Exists for tests.",
        "tags": ["test"],
        "genres": {"28": 9, "35": 8, "18": 8, "27": 2, "99": 5},
        "moods": {"cozy": 7},
        "favorites": [
            {"title": "Toy Story", "year": 1995},
            {"title": "The Godfather", "year": 1972},
            {"title": "Amélie", "year": 2001},
        ],
        "anchorRatings": [{"title": "Halloween", "year": 1978, "score": 8}],
        "volume": "light",
        "rater": {"bias": 0.0, "spread": 1.0},
        "popularity": "mainstream",
        "onboarded": True,
    }
    p.update(over)
    return p


def document(*personas: dict) -> dict:
    return {
        "version": 1,
        "password": "ff-persona-local-1",
        "emailDomain": "filmfuse.test",
        "personas": list(personas) or [persona()],
    }


def errors_for(**over) -> list[str]:
    return r.validate_document(document(persona(**over)))


def assert_error(errors: list[str], fragment: str) -> None:
    assert any(fragment in e for e in errors), f"expected {fragment!r} in {errors}"


# --------------------------------------------------------------------------- normalisation


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("The Godfather", "godfather"),
        ("Godfather, The", "godfather"),
        ("A Nightmare on Elm Street", "nightmare on elm street"),
        ("Haine, La", "haine"),
        ("Fabuleux destin d'Amélie Poulain, Le", "fabuleux destin d amelie poulain"),
        ("Amélie", "amelie"),
        ("WALL·E", "wall e"),
        ("Harold & Kumar", "harold and kumar"),
        ("When Harry Met Sally...", "when harry met sally"),
        ("Crazy, Stupid, Love.", "crazy stupid love"),
        ("The", "the"),
    ],
)
def test_normalize_title(raw: str, expected: str) -> None:
    assert r.normalize_title(raw) == expected


def test_split_movielens_title_alt_titles() -> None:
    names, year = r.split_movielens_title(
        "Bicycle Thieves (a.k.a. The Bicycle Thief) (Ladri di biciclette) (1948)"
    )
    assert year == 1948
    assert names == ["Bicycle Thieves", "Ladri di biciclette", "The Bicycle Thief"]


def test_split_movielens_title_without_year() -> None:
    assert r.split_movielens_title("Moonlight") == (["Moonlight"], None)


@pytest.mark.parametrize(
    ("title", "year", "movie_id"),
    [
        ("Toy Story", 1995, 1),
        ("toy story", 1995, 1),
        ("The Godfather", 1972, 2),  # trailing article in MovieLens
        ("Amélie", 2001, 3),  # accent + alt title
        ("Amelie", 2001, 3),
        ("Le Fabuleux destin d'Amélie Poulain", 2001, 3),  # alt title, leading article
        ("Se7en", 1995, 4),  # a.k.a. alt title
        ("Seven", 1995, 4),
        ("La Haine", 1995, 5),  # foreign article in alt title
        ("The Room", 2003, 8),
        ("Halloween", 1978, 10),  # exact year beats the remake
        ("Halloween", 2007, 11),
        (
            "Birdman: Or (The Unexpected Virtue of Ignorance)",
            2014,
            12,
        ),  # real parentheses
        ("The Blind Side", 2009, 16),  # double space before the year
    ],
)
def test_match(index: r.MovieIndex, title: str, year: int, movie_id: int) -> None:
    assert index.match(title, year).movie_id == movie_id


def test_match_year_plus_minus_one_when_unique(index: r.MovieIndex) -> None:
    assert index.match("Army of Darkness", 1992).movie_id == 9
    assert index.match("Army of Darkness", 1994).movie_id == 9


def test_match_year_off_by_two_fails(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="not found"):
        index.match("Army of Darkness", 1991)


def test_match_year_plus_minus_one_ambiguous(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="ambiguous"):
        index.match("Twin", 2011)


def test_match_exact_year_ambiguous(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="ambiguous"):
        index.match("Emma", 1996)


def test_match_unknown_title(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="not found"):
        index.match("Not A Real Movie", 2001)


def test_match_requires_year_for_yearless_movielens_rows(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="not found"):
        index.match("Moonlight", 2016)


def test_resolve_title_sources(index: r.MovieIndex) -> None:
    assert r.resolve_title("Toy Story", 1995, index, {}) == (1001, "movielens")
    assert r.resolve_title("Parasite", 2019, index, {"Parasite (2019)": 496243}) == (
        496243,
        "overrides",
    )


def test_override_takes_precedence_over_movielens(index: r.MovieIndex) -> None:
    assert r.resolve_title("Toy Story", 1995, index, {"Toy Story (1995)": 42}) == (
        42,
        "overrides",
    )


def test_override_fixes_ambiguity(index: r.MovieIndex) -> None:
    assert r.resolve_title("Emma", 1996, index, {"Emma (1996)": 3573}) == (
        3573,
        "overrides",
    )


def test_resolve_title_missing_tmdb_link(index: r.MovieIndex) -> None:
    with pytest.raises(r.ResolveError, match="no TMDB link"):
        r.resolve_title("No Link Movie", 2000, index, {})


def test_movielens_load_reads_csvs(tmp_path: Path) -> None:
    (tmp_path / "movies.csv").write_text(
        'movieId,title,genres\n1,Toy Story (1995),Animation\n2,"Godfather, The (1972)",Crime\n',
        encoding="utf-8",
    )
    (tmp_path / "links.csv").write_text(
        "movieId,imdbId,tmdbId\n1,0114709,862\n2,0068646,\n", encoding="utf-8"
    )
    index = r.MovieIndex.load(tmp_path)
    assert index.match("Toy Story", 1995).tmdb_id == 862
    assert index.match("The Godfather", 1972).tmdb_id is None


def test_movielens_load_missing_dir(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        r.MovieIndex.load(tmp_path)


# --------------------------------------------------------------------------- validation


def test_valid_fixture_has_no_errors() -> None:
    assert r.validate_document(document()) == []


def test_document_level_errors() -> None:
    assert r.validate_document([]) == ["document must be a JSON object"]
    errors = r.validate_document(
        {"version": 2, "password": "short", "emailDomain": "nodot", "personas": []}
    )
    assert_error(errors, "version must be 1")
    assert_error(errors, "password")
    assert_error(errors, "emailDomain")
    assert_error(errors, "personas must be a non-empty list")


@pytest.mark.parametrize(
    ("over", "fragment"),
    [
        ({"key": "Bad Key"}, "key must be kebab-case"),
        ({"key": "trailing-"}, "key must be kebab-case"),
        ({"username": "ab"}, "username must be"),
        ({"username": "Has-Caps"}, "username must be"),
        ({"username": "x" * 31}, "username must be"),
        ({"displayName": ""}, "displayName"),
        ({"bio": None}, "bio"),
        ({"tags": "notalist"}, "tags must be a list"),
        (
            {"genres": {"28": 9, "35": 8, "18": 8, "999": 5, "99": 5}},
            "unknown genre id '999'",
        ),
        (
            {"genres": {"28": 11, "35": 8, "18": 8, "27": 2, "99": 5}},
            "score out of 1-10",
        ),
        (
            {"genres": {"28": 0, "35": 8, "18": 8, "27": 9, "99": 5}},
            "score out of 1-10",
        ),
        ({"genres": {"28": 9, "35": 8, "18": 8, "27": 2}}, "onboarding rule"),
        ({"genres": {"28": 9, "35": 8, "18": 7, "27": 2, "99": 5}}, "onboarding rule"),
        ({"genres": []}, "genres must be an object"),
        ({"moods": {"spooky": 5}}, "unknown mood 'spooky'"),
        ({"moods": {"cozy": 12}}, "mood cozy score out of 1-10"),
        (
            {"favorites": [{"title": "Toy Story", "year": 1995}]},
            "favorites must have 3-10",
        ),
        (
            {"favorites": [{"title": "Toy Story", "year": 1995 + i} for i in range(11)]},
            "favorites must have 3-10",
        ),
        ({"favorites": "nope"}, "favorites must be a list"),
        (
            {"anchorRatings": [{"title": "Halloween", "year": 1978, "score": 0}]},
            "score out",
        ),
        (
            {"anchorRatings": [{"title": "Halloween", "year": 1978, "score": 11}]},
            "score out",
        ),
        ({"anchorRatings": [{"title": "Halloween", "year": 1978}]}, "score out"),
        ({"anchorRatings": [{"title": "", "year": 1978, "score": 5}]}, "title must be"),
        (
            {"anchorRatings": [{"title": "X", "year": "1978", "score": 5}]},
            "year must be",
        ),
        ({"anchorRatings": ["Halloween"]}, "must be an object with title and year"),
        (
            {"anchorRatings": [{"title": f"M{i}", "year": 2000, "score": 5} for i in range(41)]},
            "max 40",
        ),
        ({"volume": "huge"}, "volume must be one of"),
        ({"rater": {"bias": 4, "spread": 1.0}}, "rater.bias"),
        ({"rater": {"bias": 0, "spread": 0.1}}, "rater.spread"),
        ({"rater": {"bias": 0, "spread": 2.5}}, "rater.spread"),
        ({"rater": None}, "rater must be an object"),
        ({"era": {"from": 2000, "to": 1990}}, "era must be"),
        ({"popularity": "viral"}, "popularity must be one of"),
        ({"comfort": {"maxCertification": "PG-13"}}, "maxCertification invalid"),
        ({"comfort": {"contentLimits": {"GORE": "MILD"}}}, "contentLimits key invalid"),
        ({"comfort": {"contentLimits": {"VIOLENCE": "SOME"}}}, "value invalid"),
        ({"comfort": {"contentLimits": {"VIOLENCE": "NONE"}}}, "lowest is MILD"),
        ({"comfort": {"contentLimits": {"FRIGHTENING": "NONE"}}}, "lowest is MILD"),
        ({"comfort": {"allowUnrated": "yes"}}, "allowUnrated"),
        ({"comfort": "strict"}, "comfort must be an object"),
        ({"onboarded": "no"}, "onboarded must be true or false"),
    ],
)
def test_persona_validation_errors(over: dict, fragment: str) -> None:
    assert_error(errors_for(**over), fragment)


def test_comfort_accepts_null_certification_and_all_enums() -> None:
    comfort = {
        "maxCertification": None,
        "contentLimits": {k: "MILD" if k in r.MILD_FLOOR_KEYS else "NONE" for k in r.CONTENT_KEYS},
        "allowUnrated": True,
    }
    assert errors_for(comfort=comfort) == []
    for cert in r.CERTIFICATIONS:
        assert errors_for(comfort={"maxCertification": cert}) == []


def test_onboarding_rule_applies_to_not_onboarded_persona() -> None:
    errors = errors_for(onboarded=False, genres={"28": 9, "35": 9})
    assert_error(errors, "onboarding rule")


def test_same_movie_twice_within_favorites() -> None:
    favs = [
        {"title": "Toy Story", "year": 1995},
        {"title": "toy story", "year": 1995},
        {"title": "Amélie", "year": 2001},
    ]
    assert_error(errors_for(favorites=favs), "same movie twice")


def test_same_movie_in_favorites_and_anchors() -> None:
    anchors = [{"title": "The Godfather", "year": 1972, "score": 10}]
    assert_error(errors_for(anchorRatings=anchors), "same movie twice")


def test_same_movie_twice_detected_by_tmdb_id(index: r.MovieIndex) -> None:
    # "Se7en" and "Seven" normalise differently but resolve to one movie.
    p = persona(
        favorites=[
            {"title": "Se7en", "year": 1995},
            {"title": "Seven", "year": 1995},
            {"title": "Toy Story", "year": 1995},
        ],
        anchorRatings=[],
    )
    assert r.validate_document(document(p)) == []
    _, errors = r.resolve_document(document(p), index, {})
    assert_error(errors, "same movie twice (tmdbId 1004)")


def test_duplicate_key_username_email() -> None:
    a = persona()
    b = persona(username="TEST_PERSON")  # same key -> duplicate key + email
    errors = r.validate_document(document(a, b))
    assert_error(errors, "duplicate key: test-person")
    assert_error(errors, "duplicate email: test-person@filmfuse.test")
    c = persona(key="other-person")
    errors = r.validate_document(document(a, c))
    assert_error(errors, "duplicate username: test_person")
    assert not any("duplicate key" in e for e in errors)


def test_validate_overrides() -> None:
    assert r.validate_overrides({"Parasite (2019)": 496243}) == []
    assert_error(r.validate_overrides({"Parasite": 496243}), "must look like")
    assert_error(r.validate_overrides({"Parasite (2019)": "496243"}), "positive integer")
    assert_error(r.validate_overrides({"Parasite (2019)": 0}), "positive integer")
    assert_error(r.validate_overrides([]), "must be an object")


# --------------------------------------------------------------------------- resolution


def test_resolve_document_adds_ids_and_counts(index: r.MovieIndex) -> None:
    doc = document()
    resolved, errors = r.resolve_document(doc, index, {"Amélie (2001)": 194})
    assert errors == []
    p = resolved["personas"][0]
    assert [f["tmdbId"] for f in p["favorites"]] == [1001, 1002, 194]
    assert p["anchorRatings"][0]["tmdbId"] == 1010
    assert resolved["resolvedFrom"] == {"movielens": 3, "overrides": 1}
    assert "tmdbId" not in doc["personas"][0]["favorites"][0], "input must not be mutated"


def test_resolve_document_reports_every_unresolved(index: r.MovieIndex) -> None:
    p = persona(
        favorites=[
            {"title": "Nope One", "year": 2001},
            {"title": "Nope Two", "year": 2002},
            {"title": "Emma", "year": 1996},
        ]
    )
    _, errors = r.resolve_document(document(p), index, {})
    assert len(errors) == 3
    assert_error(errors, "Nope One (2001)")
    assert_error(errors, "Nope Two (2002)")
    assert_error(errors, "ambiguous: Emma (1996)")


def write_movielens(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    rows = "\n".join(f'{i},"{t}",Drama' for i, t in MOVIES)
    (directory / "movies.csv").write_text(f"movieId,title,genres\n{rows}\n", encoding="utf-8")
    links = "\n".join(f"{i},0,{LINKS[i] or ''}" for i, _ in MOVIES)
    (directory / "links.csv").write_text(f"movieId,imdbId,tmdbId\n{links}\n", encoding="utf-8")


def test_main_writes_deterministic_output(tmp_path: Path) -> None:
    ml = tmp_path / "ml"
    write_movielens(ml)
    src = tmp_path / "personas.json"
    src.write_text(json.dumps(document()), encoding="utf-8")
    overrides = tmp_path / "overrides.json"
    overrides.write_text(json.dumps({"Amélie (2001)": 194}), encoding="utf-8")
    out = tmp_path / "out.json"
    args = ["--movielens", str(ml), "--input", str(src), "--overrides", str(overrides)]
    assert r.main([*args, "--output", str(out)]) == 0
    first = out.read_text(encoding="utf-8")
    assert r.main([*args, "--output", str(out)]) == 0
    assert out.read_text(encoding="utf-8") == first
    assert first == r.dump_json(json.loads(first))  # sorted keys, 2-space indent
    assert first.startswith('{\n  "emailDomain"')
    loaded = r.load_resolved(out)
    assert loaded["resolvedFrom"] == {"movielens": 3, "overrides": 1}


def test_main_check_mode_writes_nothing(tmp_path: Path) -> None:
    ml = tmp_path / "ml"
    write_movielens(ml)
    src = tmp_path / "personas.json"
    src.write_text(json.dumps(document()), encoding="utf-8")
    out = tmp_path / "out.json"
    args = [
        "--movielens",
        str(ml),
        "--input",
        str(src),
        "--overrides",
        str(tmp_path / "none"),
    ]
    assert r.main([*args, "--output", str(out), "--check"]) == 0
    assert not out.exists()


def test_main_fails_with_error_list(tmp_path: Path, capsys: pytest.CaptureFixture) -> None:
    ml = tmp_path / "ml"
    write_movielens(ml)
    bad = persona(username="x", favorites=[{"title": "Nope", "year": 2001}] * 3)
    src = tmp_path / "personas.json"
    src.write_text(json.dumps(document(bad)), encoding="utf-8")
    out = tmp_path / "out.json"
    code = r.main(
        [
            "--movielens",
            str(ml),
            "--input",
            str(src),
            "--overrides",
            str(tmp_path / "none"),
        ]
        + ["--output", str(out)]
    )
    assert code == 1
    assert not out.exists()
    err = capsys.readouterr().err
    assert "username must be" in err
    assert "same movie twice" in err
    assert "not found in MovieLens: Nope (2001)" in err


def test_run_reports_missing_movielens(tmp_path: Path) -> None:
    src = tmp_path / "personas.json"
    src.write_text(json.dumps(document()), encoding="utf-8")
    resolved, errors = r.run(src, tmp_path / "none", tmp_path / "missing", None)
    assert resolved is None
    assert_error(errors, "MovieLens files not found")


def test_load_resolved_rejects_unresolved(tmp_path: Path) -> None:
    path = tmp_path / "resolved.json"
    path.write_text(json.dumps(document()), encoding="utf-8")
    with pytest.raises(ValueError, match="has no tmdbId"):
        r.load_resolved(path)


def test_load_resolved_rejects_invalid(tmp_path: Path) -> None:
    doc = document(persona(volume="huge"))
    for p in doc["personas"]:
        for ref in p["favorites"] + p["anchorRatings"]:
            ref["tmdbId"] = 1
    path = tmp_path / "resolved.json"
    path.write_text(json.dumps(doc), encoding="utf-8")
    with pytest.raises(ValueError, match="volume must be one of"):
        r.load_resolved(path)


# --------------------------------------------------------------------------- real file


@pytest.fixture(scope="module")
def real() -> dict:
    return json.loads(REAL_PERSONAS.read_text(encoding="utf-8"))


def test_real_personas_validate(real: dict) -> None:
    assert r.validate_document(real) == []
    overrides = json.loads(REAL_OVERRIDES.read_text(encoding="utf-8"))
    assert r.validate_overrides(overrides) == []


@needs_movielens
def test_real_personas_fully_resolve(real: dict, tmp_path: Path) -> None:
    resolved, errors = r.run(REAL_PERSONAS, REAL_OVERRIDES, MOVIELENS_DIR, tmp_path / "out.json")
    assert errors == []
    assert resolved is not None
    total = sum(len(p["favorites"]) + len(p["anchorRatings"]) for p in real["personas"])
    counts = resolved["resolvedFrom"]
    assert counts["movielens"] + counts["overrides"] == total
    loaded = r.load_resolved(tmp_path / "out.json")
    assert len(loaded["personas"]) == len(real["personas"])


@needs_movielens
def test_committed_resolved_file_is_up_to_date(tmp_path: Path) -> None:
    committed = r.DEFAULT_OUTPUT
    if not committed.is_file():
        pytest.skip("personas.resolved.json not generated yet")
    _, errors = r.run(REAL_PERSONAS, REAL_OVERRIDES, MOVIELENS_DIR, tmp_path / "out.json")
    assert errors == []
    assert committed.read_text(encoding="utf-8") == (tmp_path / "out.json").read_text(
        encoding="utf-8"
    ), "personas.resolved.json is stale: run python3 -I tools/personas/resolve.py"


def test_every_override_is_used(real: dict) -> None:
    overrides = json.loads(REAL_OVERRIDES.read_text(encoding="utf-8"))
    used = {
        r.override_key(ref["title"], ref["year"])
        for p in real["personas"]
        for ref in p["favorites"] + p["anchorRatings"]
    }
    assert set(overrides) - used == set()
    assert len(overrides) <= 60


def test_real_coverage_counts(real: dict) -> None:
    personas = real["personas"]
    assert len(personas) >= 35
    assert {p["volume"] for p in personas} == r.VOLUMES
    assert any(p["onboarded"] is False for p in personas)
    assert sum("multi-genre" in p["tags"] for p in personas) >= 6
    volumes = [p["volume"] for p in personas]
    # Realistic skew: light + medium outnumber heavy + power + none.
    light_medium = volumes.count("light") + volumes.count("medium")
    assert light_medium > len(volumes) - light_medium


def test_real_has_strict_comfort_caps(real: dict) -> None:
    comforts = [p.get("comfort") or {} for p in real["personas"]]
    assert any(c.get("maxCertification") in {"G", "PG"} for c in comforts)
    assert any(c.get("maxCertification") == "PG_13" for c in comforts)
    assert any(
        c.get("contentLimits", {}).get("FRIGHTENING") == "MILD"
        and c.get("maxCertification") is None
        for c in comforts
    )


def test_real_rater_edge_cases(real: dict) -> None:
    raters = {p["key"]: p["rater"] for p in real["personas"]}
    assert any(rt["bias"] >= 2 and rt["spread"] <= 0.5 for rt in raters.values())
    assert any(rt["bias"] <= -2 for rt in raters.values())
    assert any(rt["spread"] <= 0.3 for rt in raters.values())
    assert any(p["popularity"] == "niche" and "contrarian" in p["tags"] for p in real["personas"])


def test_real_every_genre_is_someones_top_genre(real: dict) -> None:
    covered: set[str] = set()
    for p in real["personas"]:
        top = max(p["genres"].values())
        if top >= 9:
            covered |= {g for g, s in p["genres"].items() if s == top}
    # TV Movie (10770) is covered by the Hallmark fan; no exceptions are needed.
    assert covered == set(r.GENRE_IDS)


def test_real_anchor_counts(real: dict) -> None:
    for p in real["personas"]:
        n = len(p["anchorRatings"])
        if p["volume"] == "none":
            assert n == 0, f"{p['key']}: brand-new/never-onboarded users carry no ratings"
        else:
            assert 5 <= n <= 25, f"{p['key']} has {n} anchors"


def test_real_uses_the_full_score_range(real: dict) -> None:
    for p in real["personas"]:
        if {"flat-rater", "generous-rater"} & set(p["tags"]):
            continue  # by design they never go low
        scores = [a["score"] for a in p["anchorRatings"]] + list(p["genres"].values())
        assert min(scores) <= 3, f"{p['key']} never really dislikes anything"
        assert max(scores) >= 9, f"{p['key']} never really loves anything"
