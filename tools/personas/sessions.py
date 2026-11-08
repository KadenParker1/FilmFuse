#!/usr/bin/env python3
"""Run group sessions for the seeded groups and report OUTCOME diagnostics (stdlib only).

    ANON_KEY=... python3 -I tools/personas/sessions.py [--strategies mean|all|a,b] [--groups names]

For every saved group in networks.json (ids from out/seed-state.json) and every strategy, the
creator calls POST /sessions {groupId, strategy}; this script then measures what came back.

This is NOT a recommender and NOT a ground-truth metric. It only describes the platform's results
against each member's STATED preferences (onboarding genre scores and comfort settings), so the
placeholder ranker and the owner's future model can be compared on the same seeded groups:

* affinity(member, movie) = mean of the member's stated genre scores for the movie's genres
  (genres they didn't score = neutral 5; a movie without genres = 5);
* "likely enjoys" = affinity >= 7;
* per result: min / mean / spread (max - min) of affinity across members;
* comfort: each result's certification against each member's maxCertification / allowUnrated.
  core-api guarantees this, so any violation is flagged as a BUG. (Per-dimension content levels
  are not exposed by the API, so contentLimits can't be checked from here.)

POST /sessions is rate limited to 30/hour per user: a 429 is recorded in the report (with its
Retry-After) and the rest of that creator's sessions are skipped unless --wait-on-limit is set.

Writes out/session-report.md and out/session-report.json. Sessions are left OPEN (members can
vote in the app) unless --end is given.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402
import networks as networks_mod  # noqa: E402
import seed  # noqa: E402

STRATEGIES = ["mean", "least_misery", "most_pleasure", "closeness_weighted", "fairness"]
ENJOY_THRESHOLD = 7.0
DEFAULT_REPORT = common.OUT_DIR / "session-report.md"
DISCLAIMER = (
    "> **Diagnostic, not ground truth.** Every number here compares the platform's results with "
    "the members' *stated* onboarding preferences (genre scores, comfort settings) of synthetic "
    "test personas. It says nothing about what real people would enjoy and is not an evaluation "
    "metric for the recommendation algorithm - use it to compare runs (placeholder vs. the "
    "owner's model) on the same seeded groups."
)


@dataclass
class Member:
    key: str
    user_id: str
    username: str
    genres: dict
    comfort: dict  # normalised


def comfort_violation(member: Member, certification: str | None) -> str | None:
    """Why `certification` (core-api enum name, e.g. "PG_13", or None) breaks member's cap."""
    cap = member.comfort.get("maxCertification")
    if not cap:
        return None
    if certification is None:
        if not member.comfort.get("allowUnrated"):
            return (
                f"unrated movie but {member.key} caps at {common.CERT_LABELS[cap]} without unrated"
            )
        return None
    cert = certification.upper().replace("-", "_")
    if cert not in common.CERTIFICATIONS:
        return f"unknown certification {certification!r}"
    if common.CERTIFICATIONS.index(cert) > common.CERTIFICATIONS.index(cap):
        return f"{common.CERT_LABELS[cert]} exceeds {member.key}'s cap {common.CERT_LABELS[cap]}"
    return None


def diagnose(results: list[dict], members: list[Member]) -> dict:
    """results: [{rank, movieId, title, year, certification, genreIds}] -> diagnostics."""
    rows = []
    violations = []
    per_member = {m.key: [] for m in members}
    for r in results:
        aff = {
            m.key: round(common.stated_affinity(m.genres, r.get("genreIds") or []), 2)
            for m in members
        }
        for m in members:
            per_member[m.key].append(aff[m.key])
        vals = list(aff.values()) or [common.NEUTRAL]
        bad = [v for m in members if (v := comfort_violation(m, r.get("certification")))]
        violations += [
            {"rank": r["rank"], "movieId": r["movieId"], "title": r["title"], "reason": b}
            for b in bad
        ]
        rows.append(
            {
                **r,
                "affinity": aff,
                "min": round(min(vals), 2),
                "mean": round(sum(vals) / len(vals), 2),
                "spread": round(max(vals) - min(vals), 2),
                "violations": bad,
            }
        )
    member_rows = {}
    for m in members:
        vals = per_member[m.key]
        member_rows[m.key] = {
            "username": m.username,
            "meanAffinity": round(sum(vals) / len(vals), 2) if vals else None,
            "enjoyShare": round(sum(v >= ENJOY_THRESHOLD for v in vals) / len(vals), 3)
            if vals
            else None,
            "comfort": common.comfort_summary(m.comfort),
        }
    n = len(rows)
    summary = {
        "results": n,
        "meanOfMeans": round(sum(r["mean"] for r in rows) / n, 2) if n else None,
        "meanOfMins": round(sum(r["min"] for r in rows) / n, 2) if n else None,
        "meanSpread": round(sum(r["spread"] for r in rows) / n, 2) if n else None,
        "worstMemberEnjoyShare": min(
            (v["enjoyShare"] for v in member_rows.values() if v["enjoyShare"] is not None),
            default=None,
        ),
        "comfortViolations": len(violations),
    }
    return {"rows": rows, "members": member_rows, "summary": summary, "violations": violations}


def _pct(x: float | None) -> str:
    return "-" if x is None else f"{x * 100:.0f}%"


def _num(x: float | None) -> str:
    return "-" if x is None else f"{x:.1f}"


def render_markdown(report: dict) -> str:
    out = [
        "# Group session report (test personas)",
        "",
        DISCLAIMER,
        "",
        f"Generated {report['generatedAt']} against `{report['api']}`. Affinity = mean of a "
        "member's stated genre scores for the movie's genres (unscored genre = 5); "
        f'"enjoys" = affinity >= {ENJOY_THRESHOLD:.0f}.',
        "",
    ]
    bugs = [
        (s, v) for s in report["sessions"] for v in s.get("diagnostics", {}).get("violations", [])
    ]
    if bugs:
        out += ["## BUG: comfort violations", ""]
        out += [
            f"- **{s['group']}** / {s['strategy']}: #{v['rank']} {v['title']} - {v['reason']}"
            for s, v in bugs
        ]
        out.append("")
    out += [
        "## Summary",
        "",
        "| Group | Intent | Strategy | Results | Mean affinity | Mean of mins | Mean spread "
        "| Worst member enjoys | Comfort bugs |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for s in report["sessions"]:
        if s.get("error"):
            out.append(
                f"| {s['group']} | {s['intent']} | {s['strategy']} | {s['error']} | | | | | |"
            )
            continue
        d = s["diagnostics"]["summary"]
        out.append(
            f"| {s['group']} | {s['intent']} | {s['strategy']} | {d['results']} | "
            f"{_num(d['meanOfMeans'])} | {_num(d['meanOfMins'])} | {_num(d['meanSpread'])} | "
            f"{_pct(d['worstMemberEnjoyShare'])} | {d['comfortViolations']} |"
        )
    out.append("")
    for s in report["sessions"]:
        if s.get("error"):
            continue
        d = s["diagnostics"]
        keys = list(d["members"])
        out += [f"## {s['group']} - {s['strategy']} ({s['intent']})", ""]
        if s.get("note"):
            out += [f"_{s['note']}_", ""]
        out += [
            f"Session `{s['sessionId']}`; effective filter: {s.get('effectiveFilter') or '-'}"
            + (f"; {s['hiddenResultCount']} hidden" if s.get("hiddenResultCount") else ""),
            "",
        ]
        out += ["| Member | Comfort | Mean affinity | Enjoys |", "|---|---|---|---|"]
        for k, m in d["members"].items():
            out.append(
                f"| {k} | {m['comfort']} | {_num(m['meanAffinity'])} | {_pct(m['enjoyShare'])} |"
            )
        out += [
            "",
            "| # | Movie | Cert | Genres | " + " | ".join(keys) + " | min | mean | spread |",
            "|---|---|---|---|" + "---|" * (len(keys) + 3),
        ]
        for r in d["rows"]:
            cert = common.CERT_LABELS.get(r.get("certification") or "", "unrated")
            genres = ", ".join(common.genre_name(g) for g in r.get("genreIds") or []) or "-"
            flag = " **BUG**" if r["violations"] else ""
            year = f" ({r['year']})" if r.get("year") else ""
            cells = " | ".join(_num(r["affinity"][k]) for k in keys)
            out.append(
                f"| {r['rank']} | {r['title']}{year}{flag} | {cert} | {genres} | {cells} | "
                f"{_num(r['min'])} | {_num(r['mean'])} | {_num(r['spread'])} |"
            )
        out.append("")
    return "\n".join(out)


def parse_strategies(raw: str) -> list[str]:
    if raw == "all":
        return list(STRATEGIES)
    out = [s.strip().lower() for s in raw.split(",") if s.strip()]
    bad = [s for s in out if s not in STRATEGIES]
    if bad:
        raise ValueError(f"unknown strategy {bad}; choose from {STRATEGIES} or 'all'")
    return out


class SessionRunner:
    def __init__(
        self,
        seeder: seed.Seeder,
        *,
        wait_on_limit: bool = False,
        max_wait: float = 120.0,
        end: bool = False,
        sleeper: Callable[[float], None] = time.sleep,
        log: Callable[[str], None] = print,
    ):
        self.seeder = seeder
        self.wait_on_limit = wait_on_limit
        self.max_wait = max_wait
        self.end = end
        self.sleeper = sleeper
        self.log = log
        self.genre_cache: dict[int, list[int]] = {}
        self.limited: dict[str, str] = {}  # creator -> reason

    def members_for(self, group: dict) -> list[Member]:
        out = []
        for k in [group["creator"], *group["members"]]:
            p = self.seeder.by_key[k]
            out.append(
                Member(
                    k,
                    self.seeder.account(k, create=False).user_id,
                    p["username"],
                    p.get("genres", {}),
                    common.normalise_comfort(p.get("comfort")),
                )
            )
        return out

    def genres_for(self, creator: str, movie: dict) -> list[int]:
        if movie.get("genreIds"):
            return list(movie["genreIds"])
        mid = movie["id"]
        if mid not in self.genre_cache:
            status, body = self.seeder.call(creator, "GET", f"/movies/{mid}")
            self.genre_cache[mid] = (
                [g["id"] for g in (body or {}).get("genres", [])] if status == 200 else []
            )
        return self.genre_cache[mid]

    def create(self, creator: str, group_id: str, strategy: str):
        """POST /sessions. A 429 here means the 30/hour budget: wait only if allowed."""
        payload = {"groupId": group_id, "strategy": strategy.upper()}
        while True:
            status, body, headers = self.seeder.call_full(
                creator, "POST", "/sessions", payload, retries=0
            )
            if status != 429:
                return status, body
            raw = headers.get("retry-after")
            try:
                wait = float(raw) if raw else None
            except ValueError:
                wait = None
            if wait is None or not self.wait_on_limit or wait > self.max_wait:
                return 429, {"retryAfter": wait}
            self.log(f"    429 on POST /sessions; waiting {wait:.0f}s")
            self.sleeper(wait)

    def run(
        self, net: networks_mod.Network, group_names: list[str] | None, strategies: list[str]
    ) -> list[dict]:
        groups_state = self.seeder.state.get("groups", {})
        sessions = []
        for g in net.groups:
            if group_names and g["name"] not in group_names:
                continue
            base = {
                "group": g["name"],
                "intent": g["intent"],
                "note": g.get("note", ""),
                "members": [g["creator"], *g["members"]],
            }
            gid = (groups_state.get(g["name"]) or {}).get("id")
            for strategy in strategies:
                entry = {**base, "strategy": strategy}
                if not gid:
                    entry["error"] = "not seeded (run seed.py)"
                elif g["creator"] in self.limited:
                    entry["error"] = self.limited[g["creator"]]
                else:
                    self._one(g, gid, strategy, entry)
                sessions.append(entry)
                self.log(f"{g['name']} / {strategy}: {entry.get('error') or 'ok'}")
        return sessions

    def _one(self, g: dict, gid: str, strategy: str, entry: dict) -> None:
        creator = g["creator"]
        status, body = self.create(creator, gid, strategy)
        if status == 429:
            wait = (body or {}).get("retryAfter")
            reason = "rate limited (POST /sessions is 30/hour per user" + (
                f"; retry after {wait:.0f}s)" if wait else ")"
            )
            self.limited[creator] = reason
            entry["error"] = reason
            return
        if status != 201:
            entry["error"] = f"HTTP {status} {seed._msg(body)}"
            return
        results = []
        for r in body.get("results", []):
            movie = r["movie"]
            results.append(
                {
                    "rank": r["rank"],
                    "movieId": movie["id"],
                    "title": movie["title"],
                    "year": movie.get("releaseYear"),
                    "certification": r.get("certification"),
                    "genreIds": self.genres_for(creator, movie),
                    "groupScore": r.get("groupScore"),
                    "explanation": r.get("explanation"),
                }
            )
        members = self.members_for(g)
        entry.update(
            {
                "sessionId": body["id"],
                "effectiveFilter": (body.get("effectiveFilter") or {}).get("label"),
                "hiddenResultCount": body.get("hiddenResultCount", 0),
                "membersWithVector": [
                    m["user"]["username"] for m in body.get("members", []) if m.get("hasVector")
                ],
                "diagnostics": diagnose(results, members),
            }
        )
        if self.end:
            self.seeder.call(creator, "POST", f"/sessions/{body['id']}/end")


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_markdown(report), encoding="utf-8")
    common.write_json(path.with_suffix(".json"), report)


def main(
    argv: list[str] | None = None,
    transport: seed.Transport = seed.urllib_transport,
    sleeper: Callable[[float], None] = time.sleep,
) -> int:
    ap = argparse.ArgumentParser(
        description="Create group sessions for the seeded groups and write an OUTCOME diagnostic "
        "report (stated preferences, not ground truth)."
    )
    ap.add_argument("--personas", type=Path, default=common.DEFAULT_PERSONAS)
    ap.add_argument("--networks", type=Path, default=common.DEFAULT_NETWORKS)
    ap.add_argument("--state", type=Path, default=common.DEFAULT_SEED_STATE)
    ap.add_argument("--out", type=Path, default=DEFAULT_REPORT, help="markdown report (+ .json)")
    ap.add_argument("--api", default=common.DEFAULT_API)
    ap.add_argument("--supabase", default=common.DEFAULT_SUPABASE)
    ap.add_argument("--anon-key", default=None, help="Supabase anon key (default: $ANON_KEY)")
    ap.add_argument(
        "--strategies",
        default="mean",
        help=f"comma-separated from {','.join(STRATEGIES)}, or 'all' (default: mean; "
        "POST /sessions is limited to 30/hour per user)",
    )
    ap.add_argument("--groups", help="comma-separated group names (default: all)")
    ap.add_argument(
        "--wait-on-limit",
        action="store_true",
        help="on a 429, wait for Retry-After (up to --max-wait) instead of skipping",
    )
    ap.add_argument("--max-wait", type=float, default=120.0)
    ap.add_argument("--end", action="store_true", help="end each session after reading it")
    args = ap.parse_args(argv)
    try:
        strategies = parse_strategies(args.strategies)
        data = common.load_personas(args.personas)
        net = networks_mod.load(args.networks, data)
        state = common.load_json(args.state, "seed state (run seed.py first)")
    except (ValueError, common.PersonaFileError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    except networks_mod.NetworkError as e:
        print("networks.json is invalid:\n  - " + "\n  - ".join(e.errors), file=sys.stderr)
        return 1
    anon = args.anon_key or os.environ.get("ANON_KEY")
    if not anon:
        print("error: set ANON_KEY", file=sys.stderr)
        return 1
    api = seed.Api(transport, supabase=args.supabase, api=args.api, anon_key=anon, sleeper=sleeper)
    seeder = seed.Seeder(api, data, {}, state)
    runner = SessionRunner(
        seeder,
        wait_on_limit=args.wait_on_limit,
        max_wait=args.max_wait,
        end=args.end,
        sleeper=sleeper,
    )
    try:
        sessions = runner.run(net, common.parse_keys(args.groups), strategies)
    except seed.SeedError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    report = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "api": args.api,
        "strategies": strategies,
        "sessions": sessions,
    }
    write_report(args.out, report)
    bugs = sum(len(s.get("diagnostics", {}).get("violations", [])) for s in sessions)
    print(f"report -> {args.out} (+ .json); {len(sessions)} sessions, {bugs} comfort violation(s)")
    return 2 if bugs else 0


if __name__ == "__main__":
    sys.exit(main())
