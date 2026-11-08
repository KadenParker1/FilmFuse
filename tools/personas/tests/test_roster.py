import re

import pytest
from personas_testlib import PERSONAS_DIR, sample

import common
import export_csv
import networks

NET = {
    "clusters": [{"name": "Fam", "members": ["disney-mom", "mcu-teen", "hallmark-fan"]}],
    "groups": [
        {"name": "Night", "creator": "disney-mom", "members": ["mcu-teen"], "intent": "pair"}
    ],
}


def sections(md: str) -> dict[str, list[str]]:
    """Section heading -> persona keys listed in its table."""
    out: dict[str, list[str]] = {}
    current = None
    for line in md.splitlines():
        if line.startswith("## "):
            current = line[3:]
            out[current] = []
        elif current and line.startswith("| `"):
            out[current].append(re.match(r"\| `[^`]+`<br>([a-z0-9-]+) \|", line).group(1))
    return out


def check_roster(md: str, data: dict) -> dict[str, list[str]]:
    secs = sections(md)
    assert list(secs) == export_csv.SECTIONS
    listed = [k for keys in secs.values() for k in keys]
    assert sorted(listed) == sorted(p["key"] for p in data["personas"])  # complete...
    assert len(listed) == len(set(listed))  # ...and every persona exactly once (disjoint)
    return secs


def test_sample_roster_sections():
    data = sample()
    net = networks.build(NET, [p["key"] for p in data["personas"]])
    md = export_csv.roster_markdown(data, None, net)
    assert md.startswith("<!-- GENERATED")
    assert "GENERATED - do not edit" in md
    secs = check_roster(md, data)
    # mcu-teen: volume none + onboarded false; hallmark-fan: flat (spread 0.4);
    # disney-mom / horror-fanatic: rater bias +1.5 / -1.5
    assert set(secs[export_csv.EDGE]) == {
        "mcu-teen",
        "hallmark-fan",
        "horror-fanatic",
        "disney-mom",
    }
    assert secs[export_csv.SINGLE] == ["film-bro"]
    assert "Night (pair)" in md


def test_section_inference_rules():
    base = {
        "key": "k",
        "tags": [],
        "volume": "light",
        "rater": {"bias": 0, "spread": 1},
        "onboarded": True,
    }
    assert export_csv.roster_section(base) == export_csv.SINGLE
    assert export_csv.roster_section({**base, "tags": ["multi-genre", "edge-case"]}) == (
        export_csv.COMBO
    )
    assert export_csv.roster_section({**base, "tags": ["edge-case"]}) == export_csv.EDGE
    assert export_csv.roster_section({**base, "volume": "none"}) == export_csv.EDGE
    assert export_csv.roster_section({**base, "onboarded": False}) == export_csv.EDGE
    assert export_csv.roster_section({**base, "rater": {"bias": -2, "spread": 1}}) == (
        export_csv.EDGE
    )
    assert export_csv.roster_section({**base, "rater": {"bias": 0, "spread": 0.3}}) == (
        export_csv.EDGE
    )


def test_section_override(monkeypatch):
    monkeypatch.setitem(export_csv.SECTION_OVERRIDES, "k", export_csv.EDGE)
    assert export_csv.roster_section({"key": "k", "tags": ["multi-genre"]}) == export_csv.EDGE


def test_what_it_tests():
    g_cap = {
        "key": "k",
        "tags": ["edge-case"],
        "genres": {"16": 10},
        "volume": "light",
        "comfort": {"maxCertification": "G", "contentLimits": {"FRIGHTENING": "NONE"}},
    }
    text = export_csv.what_it_tests(g_cap)
    assert "G cap forces every group they join down to G" in text
    assert "frightening none" in text
    combo = {
        "key": "c",
        "tags": ["multi-genre"],
        "genres": {"27": 10, "10749": 9, "18": 3},
        "volume": "power",
        "popularity": "niche",
    }
    text = export_csv.what_it_tests(combo)
    assert "loves Horror + Romance" in text and "power user" in text and "long-tail" in text


def test_pipes_escaped():
    data = sample()
    data["personas"][0]["bio"] = "a | b"
    assert "a \\| b" in export_csv.roster_markdown(data, None, None)


def committed_inputs():
    resolved = PERSONAS_DIR / "personas.resolved.json"
    if not resolved.exists():
        pytest.skip("personas.resolved.json not generated yet")
    data = common.load_personas(resolved)
    net = networks.load(PERSONAS_DIR / "networks.json", data)
    gen_path = common.DEFAULT_GENERATED
    generated = common.load_json(gen_path, "generated") if gen_path.exists() else None
    return data, net, generated


def test_real_roster_is_complete_and_disjoint():
    data, net, generated = committed_inputs()
    secs = check_roster(export_csv.roster_markdown(data, generated, net), data)
    assert all(secs.values())  # no empty section


def test_committed_outputs_are_up_to_date(tmp_path):
    data, net, generated = committed_inputs()
    roster = PERSONAS_DIR / "ROSTER.md"
    assert roster.exists(), "run python3 -I tools/personas/export_csv.py"
    assert roster.read_text(encoding="utf-8") == export_csv.roster_markdown(data, generated, net), (
        "ROSTER.md is stale: run python3 -I tools/personas/export_csv.py"
    )
    for name, columns, rows in (
        (
            "accounts.csv",
            export_csv.ACCOUNTS_COLUMNS,
            export_csv.account_rows(data, generated, net),
        ),
        ("networks.csv", export_csv.NETWORK_COLUMNS, export_csv.network_rows(data, net)),
        ("groups.csv", export_csv.GROUP_COLUMNS, export_csv.group_rows(net)),
    ):
        fresh = tmp_path / name
        export_csv.write_csv(fresh, columns, rows)
        assert (PERSONAS_DIR / name).read_bytes() == fresh.read_bytes(), (
            f"{name} is stale: run python3 -I tools/personas/export_csv.py"
        )
