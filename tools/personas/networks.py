#!/usr/bin/env python3
"""Fake social networks for the test personas: validate and build networks.json (stdlib only).

    python3 -I tools/personas/networks.py validate [--personas FILE] [--networks FILE]
    python3 -I tools/personas/networks.py summary  [--personas FILE] [--networks FILE]

networks.json (hand-written) describes:

* clusters: {name, kind, members[], close: [[a, b], ...]} - every pair of members becomes an
  accepted friendship (the earlier member in the list sends the request). `close` pairs flag each
  other as close friends. A person in two clusters is a bridge.
* edges: extra edges {a, b, type: friend|pending|block, close?: true|"a"|"b", note?}.
  friend = a sends, b accepts; pending = a sends, b never answers; block = a blocks b.
  close: true = both flag the other as a close friend, "a" / "b" = only that side does.
* isolates: persona keys with no friends at all (no edges, no clusters, no groups).
* groups: saved groups {name, emoji, creator, members[], intent: easy|hard|big|pair, note?}.
  2-8 people including the creator; every member must be the creator's friend (core-api rule).

build() turns this into a deterministic, de-duplicated edge list. Validation errors are collected
and reported together.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402

EDGE_TYPES = ("friend", "pending", "block")
INTENTS = ("easy", "hard", "big", "pair")
GROUP_MIN, GROUP_MAX = 2, 8


@dataclass
class Edge:
    a: str  # sender (friend/pending) or blocker (block)
    b: str
    type: str  # friend | pending | block
    close_by: list[str] = field(default_factory=list)  # keys that flag the other as close
    source: str = ""  # cluster name(s) or "edge"
    note: str = ""

    @property
    def pair(self) -> tuple[str, str]:
        return tuple(sorted((self.a, self.b)))  # type: ignore[return-value]

    @property
    def csv_type(self) -> str:
        return "close" if self.type == "friend" and self.close_by else self.type


@dataclass
class Network:
    edges: list[Edge]
    groups: list[dict]
    clusters: list[dict]
    isolates: list[str]

    def friends_of(self, key: str) -> list[str]:
        out = []
        for e in self.edges:
            if e.type == "friend" and key in (e.a, e.b):
                out.append(e.b if e.a == key else e.a)
        return sorted(out)

    def close_friends_of(self, key: str) -> list[str]:
        """People `key` flags as a close friend (the flag is directional)."""
        out = []
        for e in self.edges:
            if e.type == "friend" and key in e.close_by:
                out.append(e.b if e.a == key else e.a)
        return sorted(out)

    def groups_of(self, key: str) -> list[str]:
        return [g["name"] for g in self.groups if key == g["creator"] or key in g["members"]]


class NetworkError(Exception):
    def __init__(self, errors: list[str]):
        super().__init__("\n".join(errors))
        self.errors = errors


def build(spec: dict, persona_keys: set[str] | list[str]) -> Network:
    """Validate `spec` against the persona keys and build the network; raises NetworkError."""
    known = set(persona_keys)
    errors: list[str] = []

    def check_key(key: object, where: str) -> bool:
        if not isinstance(key, str) or key not in known:
            errors.append(f"{where}: unknown persona key {key!r} (not in the persona file)")
            return False
        return True

    edges: dict[tuple[str, str], Edge] = {}
    clusters = spec.get("clusters", [])
    for ci, cluster in enumerate(clusters):
        cname = cluster.get("name") or f"clusters[{ci}]"
        members = cluster.get("members", [])
        dupes = [k for k, n in Counter(members).items() if n > 1]
        if dupes:
            errors.append(f"cluster {cname!r}: duplicate member(s) {dupes}")
        valid = [m for m in dict.fromkeys(members) if check_key(m, f"cluster {cname!r}")]
        for i, a in enumerate(valid):
            for b in valid[i + 1 :]:
                pair = tuple(sorted((a, b)))
                if pair in edges:  # same pair in two clusters: one friendship
                    edges[pair].source += f", {cname}"
                else:
                    edges[pair] = Edge(a, b, "friend", source=cname)
        for pi, close in enumerate(cluster.get("close", [])):
            if not (isinstance(close, list) and len(close) == 2):
                errors.append(f"cluster {cname!r}: close[{pi}] must be a pair [a, b]")
                continue
            a, b = close
            if not (
                check_key(a, f"cluster {cname!r} close")
                and check_key(b, f"cluster {cname!r} close")
            ):
                continue
            edge = edges.get(tuple(sorted((a, b))))
            if a == b or edge is None or a not in valid or b not in valid:
                errors.append(f"cluster {cname!r}: close pair {a}/{b} is not a pair of its members")
                continue
            edge.close_by = sorted(set(edge.close_by) | {a, b})

    seen_explicit: set[tuple[str, str]] = set()
    for ei, raw in enumerate(spec.get("edges", [])):
        where = f"edges[{ei}]"
        a, b, etype = raw.get("a"), raw.get("b"), raw.get("type", "friend")
        ok = check_key(a, where) & check_key(b, where)
        if etype not in EDGE_TYPES:
            errors.append(f"{where}: type must be one of {EDGE_TYPES}, got {etype!r}")
            continue
        if not ok:
            continue
        if a == b:
            errors.append(f"{where}: self-edge {a} -> {a}")
            continue
        pair = tuple(sorted((a, b)))
        if pair in seen_explicit:
            errors.append(f"{where}: duplicate edge between {a} and {b}")
            continue
        seen_explicit.add(pair)
        existing = edges.get(pair)
        if existing is not None:
            if etype == "block" and existing.type == "friend":
                errors.append(
                    f"{where}: block between friends {a} and {b} (they are friends via "
                    f"{existing.source}); a block removes the friendship, so pick strangers"
                )
            elif etype == "pending" and existing.type == "friend":
                errors.append(
                    f"{where}: pending request between {a} and {b}, who are already "
                    f"friends via {existing.source}"
                )
            else:
                errors.append(
                    f"{where}: duplicate edge between {a} and {b} (already from {existing.source})"
                )
            continue
        close = raw.get("close")
        close_by: list[str] = []
        if close:
            if etype != "friend":
                errors.append(f"{where}: close is only valid on a friend edge")
                continue
            if close is True:
                close_by = sorted((a, b))
            elif close in ("a", "b"):
                close_by = [a if close == "a" else b]
            else:
                errors.append(f'{where}: close must be true, "a" or "b"')
                continue
        edges[pair] = Edge(a, b, etype, close_by, source="edge", note=raw.get("note", ""))

    isolates = list(spec.get("isolates", []))
    for k in isolates:
        if check_key(k, "isolates"):
            touching = [e for e in edges.values() if k in (e.a, e.b)]
            if touching:
                errors.append(f"isolates: {k} has {len(touching)} edge(s); an isolate has none")
            if any(k in c.get("members", []) for c in clusters):
                errors.append(f"isolates: {k} is in a cluster")

    friends: dict[str, set[str]] = {}
    blocked: set[tuple[str, str]] = set()
    for e in edges.values():
        if e.type == "friend":
            friends.setdefault(e.a, set()).add(e.b)
            friends.setdefault(e.b, set()).add(e.a)
        elif e.type == "block":
            blocked.add(e.pair)

    groups = spec.get("groups", [])
    names = Counter(g.get("name") for g in groups)
    for gi, g in enumerate(groups):
        gname = g.get("name") or f"groups[{gi}]"
        where = f"group {gname!r}"
        if not g.get("name") or len(str(g["name"]).strip()) > 40:
            errors.append(f"{where}: name is required, at most 40 characters")
        if names[g.get("name")] > 1:
            errors.append(f"{where}: duplicate group name (names identify groups on re-runs)")
        if g.get("intent") not in INTENTS:
            errors.append(f"{where}: intent must be one of {INTENTS}, got {g.get('intent')!r}")
        creator = g.get("creator")
        members = g.get("members", [])
        if not check_key(creator, f"{where} creator"):
            continue
        if creator in members:
            errors.append(f"{where}: the creator {creator} is listed as a member")
        if len(set(members)) != len(members):
            errors.append(f"{where}: duplicate members")
        size = 1 + len(set(members) - {creator})
        if not GROUP_MIN <= size <= GROUP_MAX:
            errors.append(
                f"{where}: {size} people incl. the creator; must be {GROUP_MIN}-{GROUP_MAX}"
            )
        if g.get("intent") == "pair" and size != 2:
            errors.append(f"{where}: intent pair needs exactly 2 people, has {size}")
        if g.get("intent") == "big" and size < 6:
            errors.append(f"{where}: intent big needs 6-8 people, has {size}")
        for m in members:
            if not check_key(m, f"{where} member") or m == creator:
                continue
            if m not in friends.get(creator, set()):
                errors.append(
                    f"{where}: member {m} is not a friend of the creator {creator} "
                    "(core-api only lets you add your friends)"
                )
            if tuple(sorted((creator, m))) in blocked:
                errors.append(f"{where}: member {m} and creator {creator} have a block")

    if errors:
        raise NetworkError(errors)
    type_order = {"friend": 0, "pending": 1, "block": 2}
    ordered = sorted(edges.values(), key=lambda e: (type_order[e.type], e.pair))
    return Network(ordered, list(groups), list(clusters), isolates)


def load(networks_path: Path | str | None, personas: dict) -> Network:
    spec = common.load_json(networks_path or common.DEFAULT_NETWORKS, "networks file")
    return build(spec, common.personas_by_key(personas).keys())


def summary(net: Network, personas: dict) -> str:
    keys = [p["key"] for p in personas["personas"]]
    counts = Counter(e.csv_type for e in net.edges)
    lines = [
        f"clusters: {len(net.clusters)}",
        *(
            f"  - {c['name']} ({c.get('kind', '')}): {', '.join(c['members'])}"
            for c in net.clusters
        ),
        "edges: "
        + ", ".join(f"{t} {counts.get(t, 0)}" for t in ("friend", "close", "pending", "block")),
        f"groups: {len(net.groups)}",
        *(
            f"  - [{g['intent']}] {g.get('emoji', '')} {g['name']}: {g['creator']} + "
            f"{', '.join(g['members'])}"
            for g in net.groups
        ),
    ]
    bridges = [k for k in keys if sum(k in c["members"] for c in net.clusters) > 1]
    no_friends = [k for k in keys if not net.friends_of(k)]
    lines.append(f"bridges: {', '.join(bridges) or '-'}")
    lines.append(f"isolates (declared): {', '.join(net.isolates) or '-'}")
    lines.append(f"no friends at all: {', '.join(no_friends) or '-'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Validate / summarise the fake social networks "
        "(networks.json) against the persona file."
    )
    ap.add_argument("command", choices=["validate", "summary"], nargs="?", default="validate")
    ap.add_argument("--personas", type=Path, default=common.DEFAULT_PERSONAS)
    ap.add_argument("--networks", type=Path, default=common.DEFAULT_NETWORKS)
    args = ap.parse_args(argv)
    try:
        personas = common.load_personas(args.personas)
        net = load(args.networks, personas)
    except common.PersonaFileError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except NetworkError as e:
        print(f"networks.json is invalid ({len(e.errors)} problem(s)):", file=sys.stderr)
        for err in e.errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    if args.command == "summary":
        print(summary(net, personas))
    else:
        counts = Counter(e.csv_type for e in net.edges)
        print(f"OK: {len(net.edges)} edges ({dict(counts)}), {len(net.groups)} groups")
    return 0


if __name__ == "__main__":
    sys.exit(main())
