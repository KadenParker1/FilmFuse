import copy
import json

import pytest
from personas_testlib import PERSONAS_DIR, sample

import common
import networks

KEYS = ["disney-mom", "horror-fanatic", "film-bro", "mcu-teen", "hallmark-fan"]


def spec(**over):
    base = {
        "clusters": [
            {
                "name": "Family",
                "members": ["disney-mom", "mcu-teen", "hallmark-fan"],
                "close": [["disney-mom", "mcu-teen"]],
            },
        ],
        "edges": [
            {"a": "disney-mom", "b": "film-bro", "type": "friend", "close": "a"},
            {"a": "mcu-teen", "b": "film-bro", "type": "pending"},
            {"a": "horror-fanatic", "b": "hallmark-fan", "type": "block"},
        ],
        "isolates": [],
        "groups": [
            {
                "name": "Night",
                "emoji": "x",
                "creator": "disney-mom",
                "members": ["mcu-teen", "film-bro"],
                "intent": "hard",
            },
            {"name": "Pair", "creator": "mcu-teen", "members": ["hallmark-fan"], "intent": "pair"},
        ],
    }
    base.update(over)
    return base


def errors_of(s, keys=KEYS):
    with pytest.raises(networks.NetworkError) as exc:
        networks.build(s, keys)
    return "\n".join(exc.value.errors)


def test_build_valid():
    net = networks.build(spec(), KEYS)
    types = [(e.a, e.b, e.csv_type) for e in net.edges]
    assert ("disney-mom", "mcu-teen", "close") in types
    assert ("disney-mom", "hallmark-fan", "friend") in types  # earlier cluster member sends
    assert ("mcu-teen", "film-bro", "pending") in types
    assert ("horror-fanatic", "hallmark-fan", "block") in types
    assert net.friends_of("disney-mom") == ["film-bro", "hallmark-fan", "mcu-teen"]
    assert net.close_friends_of("disney-mom") == ["film-bro", "mcu-teen"]
    assert net.close_friends_of("film-bro") == []  # one-way flag
    assert net.groups_of("mcu-teen") == ["Night", "Pair"]
    assert net.friends_of("horror-fanatic") == []


def test_deterministic():
    a = networks.build(spec(), KEYS)
    b = networks.build(copy.deepcopy(spec()), list(reversed(KEYS)))
    assert [(e.a, e.b, e.type, e.close_by) for e in a.edges] == [
        (e.a, e.b, e.type, e.close_by) for e in b.edges
    ]


def test_bridge_pair_in_two_clusters_is_one_friendship():
    s = spec(
        clusters=[
            {"name": "A", "members": ["disney-mom", "mcu-teen"]},
            {"name": "B", "members": ["mcu-teen", "disney-mom", "hallmark-fan"]},
        ]
    )
    net = networks.build(s, KEYS)
    pairs = [e.pair for e in net.edges if e.type == "friend"]
    assert len(pairs) == len(set(pairs))


def test_unknown_key():
    s = spec(edges=[{"a": "disney-mom", "b": "nobody", "type": "friend"}])
    assert "unknown persona key 'nobody'" in errors_of(s)


def test_unknown_key_in_cluster_and_group():
    s = spec(
        clusters=[{"name": "C", "members": ["disney-mom", "ghost"]}],
        edges=[],
        groups=[{"name": "G", "creator": "ghost2", "members": ["disney-mom"], "intent": "pair"}],
    )
    err = errors_of(s)
    assert "'ghost'" in err and "'ghost2'" in err


def test_group_member_not_friend_of_creator():
    s = spec(
        groups=[{"name": "G", "creator": "film-bro", "members": ["hallmark-fan"], "intent": "pair"}]
    )
    assert "not a friend of the creator film-bro" in errors_of(s)


def test_group_too_small():
    s = spec(groups=[{"name": "G", "creator": "disney-mom", "members": [], "intent": "easy"}])
    assert "must be 2-8" in errors_of(s)


def test_group_too_big():
    keys = KEYS + [f"p{i}" for i in range(9)]
    s = {
        "clusters": [{"name": "All", "members": keys}],
        "groups": [
            {
                "name": "G",
                "creator": "disney-mom",
                "members": [k for k in keys if k != "disney-mom"][:8],
                "intent": "big",
            }
        ],
    }
    assert "9 people" in errors_of(s, keys)


def test_pair_and_big_intents_checked():
    s = spec(
        groups=[
            {
                "name": "G",
                "creator": "disney-mom",
                "members": ["mcu-teen", "film-bro"],
                "intent": "pair",
            }
        ]
    )
    assert "pair needs exactly 2" in errors_of(s)
    s = spec(
        groups=[{"name": "G", "creator": "disney-mom", "members": ["mcu-teen"], "intent": "big"}]
    )
    assert "big needs 6-8" in errors_of(s)


def test_self_edge():
    s = spec(edges=[{"a": "film-bro", "b": "film-bro", "type": "friend"}], groups=[])
    assert "self-edge" in errors_of(s)


def test_duplicate_edge():
    e = {"a": "film-bro", "b": "horror-fanatic", "type": "friend"}
    s = spec(edges=[e, {**e, "a": "horror-fanatic", "b": "film-bro"}], groups=[])
    assert "duplicate edge" in errors_of(s)


def test_explicit_edge_duplicating_cluster_edge():
    s = spec(edges=[{"a": "disney-mom", "b": "mcu-teen", "type": "friend"}], groups=[])
    assert "duplicate edge" in errors_of(s)


def test_block_between_friends():
    s = spec(edges=[{"a": "disney-mom", "b": "mcu-teen", "type": "block"}], groups=[])
    assert "block between friends" in errors_of(s)


def test_pending_between_friends():
    s = spec(edges=[{"a": "disney-mom", "b": "mcu-teen", "type": "pending"}], groups=[])
    assert "already friends" in errors_of(s)


def test_group_creator_blocked_member():
    s = spec(
        clusters=[],
        edges=[{"a": "film-bro", "b": "horror-fanatic", "type": "block"}],
        groups=[
            {"name": "G", "creator": "film-bro", "members": ["horror-fanatic"], "intent": "pair"}
        ],
    )
    err = errors_of(s)
    assert "not a friend" in err and "block" in err


def test_isolate_with_edges():
    s = spec(isolates=["film-bro"])
    assert "isolate has none" in errors_of(s)


def test_bad_type_and_close_on_non_friend():
    s = spec(edges=[{"a": "film-bro", "b": "horror-fanatic", "type": "enemy"}], groups=[])
    assert "type must be one of" in errors_of(s)
    s = spec(
        edges=[{"a": "film-bro", "b": "horror-fanatic", "type": "pending", "close": True}],
        groups=[],
    )
    assert "close is only valid" in errors_of(s)


def test_duplicate_group_names():
    g = {"name": "Night", "creator": "disney-mom", "members": ["mcu-teen"], "intent": "pair"}
    assert "duplicate group name" in errors_of(spec(groups=[g, g]))


def test_errors_are_collected_together():
    s = spec(
        edges=[
            {"a": "film-bro", "b": "film-bro", "type": "friend"},
            {"a": "x", "b": "film-bro", "type": "friend"},
        ],
        groups=[],
    )
    assert len(errors_of(s).splitlines()) >= 2


def test_real_networks_file_matches_real_personas():
    resolved = PERSONAS_DIR / "personas.resolved.json"
    if not resolved.exists():
        pytest.skip("personas.resolved.json not generated yet")
    data = common.load_personas(resolved)
    net = networks.load(PERSONAS_DIR / "networks.json", data)
    intents = {g["intent"] for g in net.groups}
    assert intents == {"easy", "hard", "big", "pair"}
    assert {e.type for e in net.edges} == {"friend", "pending", "block"}
    assert any(e.close_by for e in net.edges)
    assert all(not net.friends_of(k) for k in net.isolates) and len(net.isolates) >= 2


def test_cli_validate(tmp_path, capsys):
    p = tmp_path / "p.json"
    p.write_text(json.dumps(sample()))
    n = tmp_path / "n.json"
    n.write_text(json.dumps(spec()))
    assert networks.main(["validate", "--personas", str(p), "--networks", str(n)]) == 0
    assert networks.main(["summary", "--personas", str(p), "--networks", str(n)]) == 0
    assert "groups: 2" in capsys.readouterr().out
    n.write_text(json.dumps(spec(isolates=["nobody"])))
    assert networks.main(["validate", "--personas", str(p), "--networks", str(n)]) == 1
    assert "unknown persona key 'nobody'" in capsys.readouterr().err
