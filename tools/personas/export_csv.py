#!/usr/bin/env python3
"""Write the persona login sheet + network CSVs (stdlib only; no running stack needed).

    python3 -I tools/personas/export_csv.py [--personas FILE] [--generated FILE] [--networks FILE]

Outputs (UTF-8 with BOM, RFC 4180 via the csv module, so Excel / Numbers open them cleanly):

* tools/personas/accounts.csv - one row per persona: login, taste, favourites, friends, groups.
  The passwords are LOCAL-ONLY throwaway credentials for the `supabase start` stack (like the
  table in tools/e2e/README.md); they exist nowhere else.
* tools/personas/networks.csv - one row per edge: a, b, type (friend | close | pending | block).
* tools/personas/groups.csv - one row per saved group: group, creator, members, intent.
* tools/personas/ROSTER.md - the readable roster (GENERATED): single-lane archetypes, cross-genre
  combos, behaviour & edge cases, each with what it tests, top genres, ratings, comfort, groups.

total_ratings counts anchors + SYNTHETIC generated ratings (out/ratings.generated.json; when that
file is missing, the volume's nominal count, which is what generate.py produces).
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402
import networks as networks_mod  # noqa: E402

ACCOUNTS_COLUMNS = [
    "email",
    "password",
    "username",
    "display_name",
    "archetype",
    "bio",
    "top_genres",
    "disliked_genres",
    "moods",
    "favorite_movies",
    "anchor_loves",
    "anchor_hates",
    "rating_volume",
    "total_ratings",
    "rater_style",
    "comfort",
    "onboarded",
    "friends",
    "close_friends",
    "groups",
    "tags",
]
NETWORK_COLUMNS = ["a", "b", "type", "a_username", "b_username", "detail", "source"]
GROUP_COLUMNS = ["group", "creator", "members", "intent", "emoji", "size", "note"]

DEFAULT_ACCOUNTS = common.HERE / "accounts.csv"
DEFAULT_NETWORKS_CSV = common.HERE / "networks.csv"
DEFAULT_GROUPS_CSV = common.HERE / "groups.csv"
SEP = "; "


def _scored(items: list[tuple[str, int]]) -> str:
    return SEP.join(f"{name} {score}" for name, score in items)


def top_genres(persona: dict) -> str:
    rows = [(common.genre_name(g), int(s)) for g, s in persona.get("genres", {}).items()]
    rows.sort(key=lambda r: (-r[1], r[0]))
    return _scored([r for r in rows if r[1] > 3])


def disliked_genres(persona: dict) -> str:
    rows = [
        (common.genre_name(g), int(s)) for g, s in persona.get("genres", {}).items() if int(s) <= 3
    ]
    rows.sort(key=lambda r: (r[1], r[0]))
    return _scored(rows[:3])


def moods(persona: dict) -> str:
    rows = sorted(
        ((m, int(s)) for m, s in persona.get("moods", {}).items()), key=lambda r: (-r[1], r[0])
    )
    return _scored(rows)


def anchors(persona: dict, loves: bool) -> str:
    rows = [
        a
        for a in persona.get("anchorRatings", [])
        if (int(a["score"]) >= 8 if loves else int(a["score"]) <= 3)
    ]
    rows.sort(key=lambda a: (-int(a["score"]) if loves else int(a["score"]), a["title"]))
    return SEP.join(f"{common.title_year(a)} {a['score']}/10" for a in rows)


def total_ratings(persona: dict, generated: dict | None) -> str:
    """Anchors + generated. Without the generated file (or the persona in it), the volume's
    nominal count - which is what generate.py produces - so the committed outputs don't depend
    on the gitignored out/ directory."""
    return str(len(persona.get("anchorRatings", [])) + generated_count(persona, generated))


def generated_count(persona: dict, generated: dict | None) -> int:
    rows = (generated or {}).get(persona["key"])
    return len(rows) if rows is not None else common.VOLUMES.get(persona.get("volume", "none"), 0)


def account_rows(
    data: dict, generated: dict | None, net: networks_mod.Network | None
) -> list[dict]:
    by_key = common.personas_by_key(data)

    def usernames(keys: list[str]) -> str:
        return SEP.join(sorted(by_key[k]["username"] for k in keys))

    rows = []
    for p in data["personas"]:
        key = p["key"]
        rows.append(
            {
                "email": common.email_for(data, p),
                "password": data["password"],
                "username": p["username"],
                "display_name": p["displayName"],
                "archetype": p.get("archetype", ""),
                "bio": p.get("bio", ""),
                "top_genres": top_genres(p),
                "disliked_genres": disliked_genres(p),
                "moods": moods(p),
                "favorite_movies": SEP.join(common.title_year(f) for f in p.get("favorites", [])),
                "anchor_loves": anchors(p, loves=True),
                "anchor_hates": anchors(p, loves=False),
                "rating_volume": p.get("volume", "none"),
                "total_ratings": total_ratings(p, generated),
                "rater_style": common.rater_style(p.get("rater")),
                "comfort": common.comfort_summary(p.get("comfort")),
                "onboarded": "yes" if p.get("onboarded", True) else "no",
                "friends": usernames(net.friends_of(key)) if net else "",
                "close_friends": usernames(net.close_friends_of(key)) if net else "",
                "groups": SEP.join(net.groups_of(key)) if net else "",
                "tags": SEP.join(p.get("tags", [])),
            }
        )
    return rows


def network_rows(data: dict, net: networks_mod.Network) -> list[dict]:
    by_key = common.personas_by_key(data)
    rows = []
    for e in net.edges:
        if e.type == "friend" and e.close_by:
            who = "both" if len(e.close_by) == 2 else f"{e.close_by[0]} only"
            detail = f"close friends ({who})"
        elif e.type == "pending":
            detail = f"{e.a} sent, {e.b} never answered"
        elif e.type == "block":
            detail = f"{e.a} blocked {e.b}"
        else:
            detail = ""
        if e.note:
            detail = f"{detail}; {e.note}" if detail else e.note
        rows.append(
            {
                "a": e.a,
                "b": e.b,
                "type": e.csv_type,
                "a_username": by_key[e.a]["username"],
                "b_username": by_key[e.b]["username"],
                "detail": detail,
                "source": e.source,
            }
        )
    return rows


def group_rows(net: networks_mod.Network) -> list[dict]:
    return [
        {
            "group": g["name"],
            "creator": g["creator"],
            "members": SEP.join(g["members"]),
            "intent": g["intent"],
            "emoji": g.get("emoji", ""),
            "size": 1 + len(g["members"]),
            "note": g.get("note", ""),
        }
        for g in net.groups
    ]


def write_csv(path: Path, columns: list[str], rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=columns, quoting=csv.QUOTE_MINIMAL, lineterminator="\r\n")
        w.writeheader()
        w.writerows(rows)


# --------------------------------------------------------------------------- ROSTER.md

DEFAULT_ROSTER = common.HERE / "ROSTER.md"
SECTIONS = ["Single-lane archetypes", "Cross-genre combos", "Behaviour & edge cases"]
SINGLE, COMBO, EDGE = SECTIONS
# Personas whose section can't be inferred from tags / data. Inference covers every current
# persona; add "key": section here when a new one lands in the wrong place.
SECTION_OVERRIDES: dict[str, str] = {}
# What a tag tests when the data alone can't say it.
TAG_NOTES = {
    "contrarian": "contrarian: anchors cut against consensus (hates crowd-pleasers)",
    "omnivore": "omnivore: no clear type, scores almost every genre high",
    "critic": "professional critic voice",
    "cold-start": "cold start: only onboarding prefs, no ratings history",
}
BIAS_EXTREME = 1.5
SPREAD_FLAT = 0.4
SPREAD_VOLATILE = 1.4


def behaviour_signals(p: dict) -> list[str]:
    """Behaviour worth testing, from the persona's data (volume, onboarding, rater, comfort)."""
    out = []
    if p.get("volume", "none") == "none":
        out.append("no generated ratings (volume none)")
    if not p.get("onboarded", True):
        out.append("never finished onboarding: the app should keep redirecting to onboarding")
    rater = p.get("rater") or {}
    bias, spread = float(rater.get("bias", 0)), float(rater.get("spread", 1))
    if bias >= BIAS_EXTREME:
        out.append(f"generous rater (bias +{bias:g}): scores skew high")
    elif bias <= -BIAS_EXTREME:
        out.append(f"harsh rater (bias {bias:g}): scores skew low")
    if spread <= SPREAD_FLAT:
        out.append(f"flat rater (spread {spread:g}): nearly every score the same")
    elif spread >= SPREAD_VOLATILE:
        out.append(f"volatile rater (spread {spread:g})")
    c = common.normalise_comfort(p.get("comfort"))
    if c["maxCertification"]:
        label = common.CERT_LABELS[c["maxCertification"]]
        out.append(f"{label} cap forces every group they join down to {label}")
    limits = [f"{d.lower()} {lv.lower()}" for d, lv in c["contentLimits"].items() if lv != "STRONG"]
    if limits:
        out.append("content limits apply to every group they join (" + ", ".join(limits) + ")")
    return out


def roster_section(p: dict) -> str:
    if p["key"] in SECTION_OVERRIDES:
        return SECTION_OVERRIDES[p["key"]]
    tags = set(p.get("tags", []))
    if "multi-genre" in tags:
        return COMBO
    if "edge-case" in tags:
        return EDGE
    rater = p.get("rater") or {}
    strong = (
        p.get("volume", "none") == "none"
        or not p.get("onboarded", True)
        or abs(float(rater.get("bias", 0))) >= BIAS_EXTREME
        or float(rater.get("spread", 1)) <= SPREAD_FLAT
    )
    return EDGE if strong else SINGLE


def loved_genres(p: dict, at_least: int = 8) -> list[str]:
    rows = sorted(
        ((int(s), common.genre_name(g)) for g, s in p.get("genres", {}).items()),
        key=lambda r: (-r[0], r[1]),
    )
    return [name for score, name in rows if score >= at_least]


def what_it_tests(p: dict) -> str:
    tags = set(p.get("tags", []))
    notes: list[str] = []
    section = roster_section(p)
    loved = loved_genres(p)
    if section == COMBO:
        notes.append("cross-genre taste: loves " + " + ".join(loved[:3]))
    elif section == SINGLE:
        notes.append(f"single-lane baseline: {loved[0] if loved else 'mixed'} fan")
    notes += [TAG_NOTES[t] for t in sorted(tags) if t in TAG_NOTES]
    notes += behaviour_signals(p)
    if p.get("popularity") == "niche":
        notes.append("long-tail taste (niche popularity)")
    if p.get("volume") == "power":
        notes.append(f"power user ({common.VOLUMES['power']} generated ratings)")
    return "; ".join(notes)


def _cell(text: str) -> str:
    return str(text).replace("|", "\\|").replace("\n", " ")


def roster_markdown(data: dict, generated: dict | None, net: networks_mod.Network | None) -> str:
    rows: dict[str, list[str]] = {s: [] for s in SECTIONS}
    for p in data["personas"]:
        anchors_n = len(p.get("anchorRatings", []))
        gen_n = generated_count(p, generated)
        groups = []
        if net:
            groups = [
                f"{g['name']} ({g['intent']})"
                for g in net.groups
                if p["key"] == g["creator"] or p["key"] in g["members"]
            ]
        top = SEP.join(
            f"{n} {s}"
            for s, n in sorted(
                ((int(s), common.genre_name(g)) for g, s in p.get("genres", {}).items()),
                key=lambda r: (-r[0], r[1]),
            )[:4]
        )
        cells = [
            f"`{p['username']}`<br>{p['key']}",
            p.get("archetype", ""),
            p.get("bio", ""),
            what_it_tests(p),
            top,
            f"{p.get('volume', 'none')}: {anchors_n} anchors + {gen_n} generated = "
            f"{anchors_n + gen_n}",
            common.comfort_summary(p.get("comfort")),
            SEP.join(groups) or "-",
        ]
        rows[roster_section(p)].append("| " + " | ".join(_cell(c) for c in cells) + " |")
    out = [
        "<!-- GENERATED by tools/personas/export_csv.py - do not edit; edit personas.json / "
        "networks.json and re-run. -->",
        "# Test persona roster",
        "",
        "> **GENERATED - do not edit.** Source: `personas.resolved.json`, `networks.json` and the "
        "synthetic ratings from `generate.py`. Regenerate with "
        "`python3 -I tools/personas/export_csv.py`. Logins are in `accounts.csv` (local-only "
        f"throwaway accounts, password `{data['password']}`).",
        "",
        f"{len(data['personas'])} personas: "
        + ", ".join(f"{len(rows[s])} {s.lower()}" for s in SECTIONS)
        + ". Ratings = hand-picked anchors + SYNTHETIC generated history (test data, not "
        "recommendations).",
        "",
    ]
    header = (
        "| User | Archetype | Bio | What it tests | Top genres | Ratings | Comfort | Groups |\n"
        "|---|---|---|---|---|---|---|---|"
    )
    for s in SECTIONS:
        out += [f"## {s}", "", header, *rows[s], ""]
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Write accounts.csv (persona logins + taste), "
        "networks.csv and groups.csv. Works without the stack running."
    )
    ap.add_argument("--personas", type=Path, default=common.DEFAULT_PERSONAS)
    ap.add_argument("--generated", type=Path, default=common.DEFAULT_GENERATED)
    ap.add_argument("--networks", type=Path, default=common.DEFAULT_NETWORKS)
    ap.add_argument("--accounts-out", type=Path, default=DEFAULT_ACCOUNTS)
    ap.add_argument("--networks-out", type=Path, default=DEFAULT_NETWORKS_CSV)
    ap.add_argument("--groups-out", type=Path, default=DEFAULT_GROUPS_CSV)
    ap.add_argument("--roster-out", type=Path, default=DEFAULT_ROSTER)
    args = ap.parse_args(argv)
    try:
        data = common.load_personas(args.personas)
        generated = (
            common.load_json(args.generated, "generated ratings")
            if args.generated.exists()
            else None
        )
        net = networks_mod.load(args.networks, data) if args.networks.exists() else None
    except common.PersonaFileError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except networks_mod.NetworkError as e:
        print("networks.json is invalid:\n  - " + "\n  - ".join(e.errors), file=sys.stderr)
        return 1
    if generated is None:
        print(f"note: {args.generated} not found; ratings counts use the nominal volumes")
    write_csv(args.accounts_out, ACCOUNTS_COLUMNS, account_rows(data, generated, net))
    print(f"{len(data['personas'])} accounts -> {args.accounts_out}")
    args.roster_out.write_text(roster_markdown(data, generated, net), encoding="utf-8")
    print(f"roster -> {args.roster_out}")
    if net is None:
        print(f"note: {args.networks} not found; friends/groups columns left empty")
        return 0
    write_csv(args.networks_out, NETWORK_COLUMNS, network_rows(data, net))
    write_csv(args.groups_out, GROUP_COLUMNS, group_rows(net))
    print(f"{len(net.edges)} edges -> {args.networks_out}")
    print(f"{len(net.groups)} groups -> {args.groups_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
