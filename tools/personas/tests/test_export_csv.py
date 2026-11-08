import csv
import io
import json

import pytest
from personas_testlib import sample

import export_csv
import networks

NET = {
    "clusters": [
        {
            "name": "Fam",
            "members": ["disney-mom", "mcu-teen", "hallmark-fan"],
            "close": [["disney-mom", "mcu-teen"]],
        }
    ],
    "edges": [
        {"a": "film-bro", "b": "disney-mom", "type": "friend", "close": "a", "note": "cousins"},
        {"a": "film-bro", "b": "hallmark-fan", "type": "pending"},
        {"a": "horror-fanatic", "b": "film-bro", "type": "block"},
    ],
    "groups": [
        {
            "name": 'Night, "the" one',
            "emoji": "x",
            "creator": "disney-mom",
            "members": ["mcu-teen", "film-bro"],
            "intent": "hard",
            "note": "n",
        }
    ],
}
GEN = {"disney-mom": [{"tmdbId": 1, "title": "t", "year": 2000, "score": 5}] * 4}


@pytest.fixture
def paths(tmp_path):
    data = sample()
    data["personas"][0]["favorites"][0]["title"] = 'Moana, the "Chosen One"'
    files = {}
    for name, obj in (("p", data), ("g", GEN), ("n", NET)):
        files[name] = tmp_path / f"{name}.json"
        files[name].write_text(json.dumps(obj))
    out = {k: tmp_path / f"{k}.csv" for k in ("accounts", "networks", "groups")}
    args = [
        "--personas",
        str(files["p"]),
        "--generated",
        str(files["g"]),
        "--networks",
        str(files["n"]),
        "--accounts-out",
        str(out["accounts"]),
        "--networks-out",
        str(out["networks"]),
        "--groups-out",
        str(out["groups"]),
        "--roster-out",
        str(tmp_path / "ROSTER.md"),
    ]
    assert export_csv.main(args) == 0
    assert (tmp_path / "ROSTER.md").read_text().startswith("<!-- GENERATED")
    return out, args, files


def read(path):
    raw = path.read_bytes()
    assert raw.startswith(b"\xef\xbb\xbf")  # UTF-8 BOM for Excel / Numbers
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def test_columns_and_order(paths):
    out, *_ = paths
    with open(out["accounts"], encoding="utf-8-sig", newline="") as f:
        header = next(csv.reader(f))
    assert header == export_csv.ACCOUNTS_COLUMNS
    assert header[:3] == ["email", "password", "username"]
    assert header[-5:] == ["onboarded", "friends", "close_friends", "groups", "tags"]


def test_bom_and_round_trip(paths):
    out, *_ = paths
    rows = {r["email"]: r for r in read(out["accounts"])}
    meg = rows["disney-mom@filmfuse.test"]
    assert meg["password"] == "ff-persona-local-1"
    assert meg["favorite_movies"].startswith('Moana, the "Chosen One" (2016); Toy Story (1995)')
    assert meg["top_genres"] == "Animation 10; Family 10; Music 8; Adventure 7; Comedy 7"
    assert meg["disliked_genres"] == "Horror 1; Thriller 2"
    assert meg["moods"] == "feel-good 10; cozy 9; tearjerker 8; dark-gritty 1"
    assert meg["anchor_loves"] == "Up (2009) 10/10; Frozen (2013) 9/10"
    assert meg["anchor_hates"] == "Hereditary (2018) 2/10"
    assert meg["total_ratings"] == "7"  # 3 anchors + 4 generated
    assert meg["rater_style"] == "generous"
    assert meg["comfort"] == "max PG; frightening <= mild; violence <= mild; unrated blocked"
    assert meg["onboarded"] == "yes"
    teen = rows["mcu-teen@filmfuse.test"]
    assert teen["onboarded"] == "no" and teen["comfort"] == "max PG-13; unrated allowed"
    assert rows["film-bro@filmfuse.test"]["comfort"] == "none"
    assert rows["hallmark-fan@filmfuse.test"]["rater_style"] == "generous, flat"
    horror = rows["horror-fanatic@filmfuse.test"]
    assert horror["rater_style"] == "harsh" and '"jump scares' in horror["bio"]


def test_quoting_in_raw_file(paths):
    out, *_ = paths
    text = out["accounts"].read_bytes().decode("utf-8-sig")
    assert '"Moana, the ""Chosen One"" (2016);' in text
    assert "\r\n" in text


def test_friends_close_and_groups_columns(paths):
    out, *_ = paths
    rows = {r["username"]: r for r in read(out["accounts"])}
    meg = rows["magic_kingdom_meg"]
    assert meg["friends"] == "cozy_christmas_carol; tyler_durden_fan; webhead_jayden"
    assert meg["close_friends"] == "webhead_jayden"  # film-bro's close flag is one-way
    assert rows["tyler_durden_fan"]["close_friends"] == "magic_kingdom_meg"
    assert meg["groups"] == 'Night, "the" one'
    assert rows["final_girl_frankie"]["friends"] == ""


def test_networks_and_groups_csv(paths):
    out, *_ = paths
    edges = read(out["networks"])
    assert list(edges[0]) == export_csv.NETWORK_COLUMNS
    types = {(e["a"], e["b"]): e["type"] for e in edges}
    assert types[("disney-mom", "mcu-teen")] == "close"
    assert types[("disney-mom", "hallmark-fan")] == "friend"
    assert types[("film-bro", "hallmark-fan")] == "pending"
    assert types[("horror-fanatic", "film-bro")] == "block"
    cousins = next(e for e in edges if e["a"] == "film-bro" and e["b"] == "disney-mom")
    assert cousins["detail"] == "close friends (film-bro only); cousins"
    groups = read(out["groups"])
    assert groups == [
        {
            "group": 'Night, "the" one',
            "creator": "disney-mom",
            "members": "mcu-teen; film-bro",
            "intent": "hard",
            "emoji": "x",
            "size": "3",
            "note": "n",
        }
    ]


def test_works_without_generated_or_networks(tmp_path, paths, capsys):
    _, args, files = paths
    args = list(args)
    args[args.index("--generated") + 1] = str(tmp_path / "missing.json")
    args[args.index("--networks") + 1] = str(tmp_path / "missing2.json")
    accounts = tmp_path / "a2.csv"
    args[args.index("--accounts-out") + 1] = str(accounts)
    assert export_csv.main(args) == 0
    rows = {r["email"]: r for r in read(accounts)}
    assert rows["disney-mom@filmfuse.test"]["total_ratings"] == "43"
    assert rows["disney-mom@filmfuse.test"]["friends"] == ""


def test_account_rows_is_pure():
    data = sample()
    net = networks.build(NET, [p["key"] for p in data["personas"]])
    a = export_csv.account_rows(data, GEN, net)
    assert a == export_csv.account_rows(data, GEN, net)
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=export_csv.ACCOUNTS_COLUMNS)
    w.writeheader()
    w.writerows(a)
    assert list(csv.DictReader(io.StringIO(buf.getvalue()))) == a
