import copy
import json

import pytest
from personas_testlib import fake_movielens, sample, write_movielens

import common
import generate


@pytest.fixture
def ml_dir(tmp_path):
    return fake_movielens(tmp_path / "ml", n_per_genre=60)


@pytest.fixture
def candidates(ml_dir):
    return generate.load_movielens(ml_dir)


def persona(**over):
    base = {
        "key": "tester",
        "genres": {"27": 10, "10749": 2, "16": 5, "18": 5},
        "favorites": [],
        "anchorRatings": [],
        "volume": "medium",
        "rater": {"bias": 0.0, "spread": 1.0},
        "popularity": "mixed",
    }
    base.update(over)
    return base


def test_load_movielens_maps_genres_titles_and_popularity(tmp_path):
    d = write_movielens(
        tmp_path / "ml",
        [
            (1, "Matrix, The (1999)", "Action|Sci-Fi|IMAX", 603, 5),
            (2, "Toy Story (1995)", "Animation|Children", 862, 1),
            (3, "Duplicate (2000)", "Drama", 603, 9),  # same TMDB id: first wins
            (4, "No Year", "Film-Noir", 999, 0),
        ],
    )
    got = {c.tmdb_id: c for c in generate.load_movielens(d)}
    assert set(got) == {603, 862, 999}
    assert got[603].title == "The Matrix" and got[603].year == 1999
    assert got[603].genre_ids == (28, 878)
    assert got[862].genre_ids == (16, 10751)
    assert got[999].genre_ids == (80,) and got[999].year is None
    assert got[603].popularity > got[862].popularity


def test_missing_movielens_is_a_clear_error(tmp_path):
    with pytest.raises(common.PersonaFileError, match="download_movielens"):
        generate.load_movielens(tmp_path)


def test_deterministic(candidates):
    p = persona()
    assert generate.generate_for(p, candidates) == generate.generate_for(
        copy.deepcopy(p), candidates
    )
    other = generate.generate_for(persona(key="someone-else"), candidates)
    assert other != generate.generate_for(p, candidates)


@pytest.mark.parametrize("volume,count", list(common.VOLUMES.items()))
def test_volume_counts(tmp_path, volume, count):
    cands = generate.load_movielens(fake_movielens(tmp_path / "big", n_per_genre=50))
    assert len(generate.generate_for(persona(volume=volume), cands)) == count


def test_volume_capped_by_pool(tmp_path):
    cands = generate.load_movielens(fake_movielens(tmp_path / "tiny", n_per_genre=2))
    assert len(generate.generate_for(persona(volume="power"), cands)) == len(cands)


def test_no_duplicates_anchors_or_favourites_and_score_range(candidates):
    reserved = [c.tmdb_id for c in candidates[:30]]
    p = persona(
        volume="heavy",
        favorites=[{"title": "x", "year": 2000, "tmdbId": t} for t in reserved[:10]],
        anchorRatings=[
            {"title": "y", "year": 2000, "score": 5, "tmdbId": t} for t in reserved[10:]
        ],
    )
    rows = generate.generate_for(p, candidates)
    ids = [r["tmdbId"] for r in rows]
    assert len(ids) == len(set(ids)) == 120
    assert not set(ids) & set(reserved)
    assert all(1 <= r["score"] <= 10 and isinstance(r["score"], int) for r in rows)
    assert set(rows[0]) == {"tmdbId", "title", "year", "score"}


def mean(rows):
    return sum(r["score"] for r in rows) / len(rows)


def test_generous_rates_higher_than_harsh(candidates):
    generous = generate.generate_for(persona(rater={"bias": 2.0, "spread": 1.0}), candidates)
    harsh = generate.generate_for(persona(rater={"bias": -2.0, "spread": 1.0}), candidates)
    assert mean(generous) > mean(harsh) + 2


def test_flat_rater_has_low_variance(candidates):
    def var(rows):
        m = mean(rows)
        return sum((r["score"] - m) ** 2 for r in rows) / len(rows)

    flat = generate.generate_for(
        persona(volume="heavy", rater={"bias": 0, "spread": 0.3}), candidates
    )
    wild = generate.generate_for(
        persona(volume="heavy", rater={"bias": 0, "spread": 1.8}), candidates
    )
    assert var(flat) < var(wild)


def test_genre_affinity_reflected(candidates):
    by_id = {c.tmdb_id: c for c in candidates}
    rows = generate.generate_for(persona(volume="heavy"), candidates)
    horror = [r for r in rows if 27 in by_id[r["tmdbId"]].genre_ids]
    romance = [r for r in rows if 10749 in by_id[r["tmdbId"]].genre_ids]
    assert horror and len(horror) > len(romance)  # selection follows taste...
    assert romance  # ...but some off-taste watches slip in
    assert mean(horror) > mean(romance) + 3  # and scores follow stated affinity


def test_popularity_skew(candidates):
    by_id = {c.tmdb_id: c for c in candidates}

    def avg_pop(pop):
        rows = generate.generate_for(persona(volume="heavy", popularity=pop), candidates)
        return sum(by_id[r["tmdbId"]].popularity for r in rows) / len(rows)

    assert avg_pop("mainstream") > avg_pop("mixed") > avg_pop("niche")


def test_era_preference(candidates):
    by_id = {c.tmdb_id: c for c in candidates}
    rows = generate.generate_for(
        persona(volume="heavy", era={"from": 1960, "to": 1975}), candidates
    )
    inside = sum(1960 <= by_id[r["tmdbId"]].year <= 1975 for r in rows)
    share_in_pool = sum(1960 <= c.year <= 1975 for c in candidates) / len(candidates)
    assert inside / len(rows) > share_in_pool + 0.2


def test_cli_writes_output(tmp_path, ml_dir):
    data = sample()
    pfile = tmp_path / "p.json"
    pfile.write_text(json.dumps(data))
    out = tmp_path / "out" / "gen.json"
    assert (
        generate.main(["--personas", str(pfile), "--movielens", str(ml_dir), "--out", str(out)])
        == 0
    )
    got = json.loads(out.read_text())
    assert list(got) == [p["key"] for p in data["personas"]]
    for p in data["personas"]:
        assert len(got[p["key"]]) == common.VOLUMES[p["volume"]]


def test_cli_missing_personas(tmp_path, capsys):
    assert generate.main(["--personas", str(tmp_path / "nope.json")]) == 1
    assert "resolve.py" in capsys.readouterr().err
