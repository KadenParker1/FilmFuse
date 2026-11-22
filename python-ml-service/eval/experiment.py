"""Experiment runner + leaderboard: one command scores a model variant on BOTH test beds.

    ML_DATABASE_URL=... python -m eval.experiment run --name sum-knn \\
        [--recommender module:attr] [--overrides '{"cr.BETA_MAX": 1.0, "knn": {"mode": "sum"}}']
    python -m eval.experiment show            # leaderboard, best first

* MovieLens harness (real ratings, movies carry their real TMDB metadata from the local
  catalog via ML_DATABASE_URL): all eligible users, 100 groups per construction method
  (random / similar / dissimilar), strategies mean, least_misery, fairness, closeness_weighted.
  Candidates are each user's POOL_SIZE most-rated unseen movies, like core-api's live pool;
  `ml_ndcg_full` repeats the personal test over the whole catalog as a secondary check.
* Persona social-network harness (`eval.personas`): hold-out on the seeded personas, the 40
  seeded groups with real closeness, reported by intent and measured taste similarity.

Each run appends one row to eval/experiments/leaderboard.jsonl. `score` compares it with the
newest row named "baseline" run in the same mode (quick or full; the committed model): a
weighted mean of metric ratios, MovieLens first because its ratings are real (personas are
synthetic and noisy):
    0.30 ML NDCG@10 + 0.05 ML NDCG@10 (whole catalog) + 0.10 ML NDCG@10 for new users (only
  their first COLD_RATINGS ratings kept) + 0.25 ML group satisfaction (mean of
  mean/min over methods x strategies) + 0.10 ML similar-taste groups + 0.15 persona NDCG@10
  + 0.05 persona group satisfaction, each ratio clipped to RATIO_CLIP (2026-11-19: a x2.9 jump in
  the tiny persona group metric alone had added +0.19)
A score above 1.0 beats the baseline. Treat differences under ~2% as noise.

Overrides: "<module>.<CONSTANT>": value, module one of cr (content_recommender), cat (catalog),
gs (group_strategies), cf (collaborative); "knn": {...} = ItemKNN keyword arguments. Variants
that need code live in eval/experiments/<name>.py and are passed with --recommender.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.models.contract import Strategy

# v3 (2026-11-19): MovieLens movies carry their real TMDB metadata from the local catalog
# (genres, keywords, votes, popularity), as in production; v2 used MovieLens genres and train
# counts (leaderboard-v2.jsonl). v2 added the production-like pool; round 1 is leaderboard.jsonl.
# v4 (2026-11-19): adds ml_cold_ndcg (new users with 5 ratings); v3 rows in leaderboard-v3.jsonl.
BOARD = Path(__file__).resolve().parent / "experiments" / "leaderboard-v4.jsonl"
COLD_RATINGS = 5
POOL_SIZE = 1000  # like core-api's ContentFilterService.POOL_SIZE
STRATEGIES = [Strategy.MEAN, Strategy.LEAST_MISERY, Strategy.FAIRNESS, Strategy.CLOSENESS_WEIGHTED]
WEIGHTS = {
    "ml_ndcg": 0.30,
    "ml_ndcg_full": 0.05,
    "ml_cold_ndcg": 0.10,
    "ml_group": 0.25,
    "ml_similar": 0.10,
    "p_ndcg": 0.15,
    "p_group": 0.05,
}
# Each metric's ratio to the baseline is clipped to this range before weighting, so one tiny,
# noisy metric (persona group satisfaction is a handful of hits) can't dominate the score.
RATIO_CLIP = (0.67, 1.5)


def apply_overrides(overrides: dict[str, Any]) -> None:
    from app.services import catalog, collaborative, content_recommender, group_strategies

    mods = {"cr": content_recommender, "cat": catalog, "gs": group_strategies, "cf": collaborative}
    for key, value in overrides.items():
        if key == "knn":
            params = dict(value)
            content_recommender.CF_MODELS["knn"] = lambda t, p=params: collaborative.ItemKNN(t, **p)
            continue
        mod, _, name = key.partition(".")
        if mod not in mods or not hasattr(mods[mod], name):
            raise SystemExit(f"unknown override {key!r}")
        setattr(mods[mod], name, value)
    cr = content_recommender
    if "cr.PRIOR_WARM_WEIGHT" in overrides and "cr.PRIOR_COLD_WEIGHT" not in overrides:
        cr.PRIOR_COLD_WEIGHT = 2 * cr.PRIOR_WARM_WEIGHT
    cr.DIM_WEIGHTS = cr._dim_weights()


def run_movielens(
    spec: str,
    quick: bool,
    pool_size: int | None,
    groups: bool = True,
    cold: int | None = None,
    seed: int = 42,
    test_fraction: float = 0.2,
    data_dir: str | None = None,
) -> dict:
    from eval.data import DEFAULT_DATA_DIR, load_movielens
    from eval.harness import EvalConfig, build_context, evaluate, load_recommender

    n_groups = (40 if quick else 100) if groups else 0
    cfg = EvalConfig(
        n_users=200 if quick else 10_000,
        n_groups=n_groups,
        group_methods=("random", "similar", "dissimilar") if groups else (),
        strategies=STRATEGIES,
        pool_size=pool_size,
        cold_ratings=cold,
        seed=seed,
        test_fraction=test_fraction,
    )
    ctx = build_context(load_movielens(data_dir or DEFAULT_DATA_DIR), cfg)
    rec = load_recommender(spec)
    rec.metadata_catalog = _tmdb_catalog()
    return evaluate(rec, ctx, spec)


_CATALOG = None


def _tmdb_catalog():
    """The local TMDB catalog (shared across runs in one process): MovieLens movies get the same
    genres, keywords, votes and popularity the live service sees. Note: TMDB's votes and
    popularity are today's, i.e. later than MovieLens's ratings - production has the same data."""
    global _CATALOG
    if _CATALOG is None:
        from app.services.catalog import PostgresCatalog

        _CATALOG = PostgresCatalog(os.environ["ML_DATABASE_URL"], background_refresh=False)
    return _CATALOG


def run_personas(spec: str, seed: int = 42) -> dict:
    from eval import personas

    url = os.environ["ML_DATABASE_URL"]
    people, groups = personas.load(url, seed)
    return personas.evaluate(personas.make_recommender(spec, url), people, groups)


def key_metrics(ml: dict, ml_full: dict, ml_cold: dict, p: dict) -> dict[str, float]:
    k = 10
    groups = [v for d in ml["group"].values() for v in d.values()]
    ml_group = sum((g[f"mean_satisfaction@{k}"] + g[f"min_satisfaction@{k}"]) / 2 for g in groups)
    similar = ml["group"].get("similar", {})
    p_groups = p["group"].get("all", {})
    return {
        "ml_ndcg": ml["individual"][f"ndcg@{k}"],
        "ml_hit": ml["individual"][f"hit_rate@{k}"],
        "ml_ndcg_full": ml_full["individual"][f"ndcg@{k}"],
        "ml_cold_ndcg": ml_cold["individual"][f"ndcg@{k}"],
        "ml_coverage": ml["individual"]["catalog_coverage"],
        "ml_group": ml_group / max(1, len(groups)),
        "ml_similar": sum(v[f"mean_satisfaction@{k}"] for v in similar.values())
        / max(1, len(similar)),
        "p_ndcg": p["individual"][f"ndcg@{k}"],
        "p_hit": p["individual"][f"hit@{k}"],
        "p_group": sum(v["mean_sat"] for v in p_groups.values()) / max(1, len(p_groups)),
        "p_similar": sum(v["mean_sat"] for v in p["group"].get("taste:similar", {}).values())
        / max(1, len(p["group"].get("taste:similar", {}))),
        "p_easy": sum(v["mean_sat"] for v in p["group"].get("intent:easy", {}).values())
        / max(1, len(p["group"].get("intent:easy", {}))),
    }


def load_board() -> list[dict]:
    if not BOARD.exists():
        return []
    return [json.loads(line) for line in BOARD.read_text().splitlines() if line.strip()]


def baseline_for(row: dict, rows: list[dict]) -> dict | None:
    """The newest "baseline" row run in the same mode (quick vs full): different user samples."""
    same = [
        r
        for r in rows
        if r["name"] == "baseline"
        and r.get("quick") == row.get("quick")
        and r.get("seed", 42) == row.get("seed", 42)
        and r.get("test_fraction", 0.2) == row.get("test_fraction", 0.2)
        and r.get("data", "ml-latest-small") == row.get("data", "ml-latest-small")
    ]
    return same[-1] if same else None


def score(row: dict, baseline: dict | None) -> float | None:
    if baseline is None:
        return None
    total = 0.0
    for name, w in WEIGHTS.items():
        base = baseline["metrics"][name]
        ratio = row["metrics"][name] / base if base else 1.0
        total += w * min(max(ratio, RATIO_CLIP[0]), RATIO_CLIP[1])
    return total


def cmd_run(args: argparse.Namespace) -> int:
    if "ML_DATABASE_URL" not in os.environ:
        print("ML_DATABASE_URL is required (ml_reader on the seeded local stack)", file=sys.stderr)
        return 2
    overrides = json.loads(args.overrides) if args.overrides else {}
    apply_overrides(overrides)
    started = time.perf_counter()
    split = {"seed": args.seed, "test_fraction": args.test_fraction, "data_dir": args.data}
    pool = args.pool_size
    ml = run_movielens(args.recommender, args.quick, pool, **split)
    ml_full = run_movielens(args.recommender, args.quick, None, groups=False, **split)
    ml_cold = run_movielens(
        args.recommender, args.quick, pool, groups=False, cold=COLD_RATINGS, **split
    )
    p = run_personas(args.recommender, args.seed)
    row = {
        "name": args.name,
        "at": datetime.now(UTC).isoformat(timespec="seconds"),
        "recommender": args.recommender,
        "overrides": overrides,
        "quick": args.quick,
        "seed": args.seed,
        "test_fraction": args.test_fraction,
        "data": args.data or "ml-latest-small",
        "pool_size": pool,
        "note": args.note,
        "metrics": key_metrics(ml, ml_full, ml_cold, p),
        "detail": {
            "ml_group": ml["group"],
            "persona_group": p["group"],
            "ml_timing_ms": [ml["timing"]["mean_user_call_ms"], ml["timing"]["mean_group_call_ms"]],
            "violations": ml["contract_violations"],
        },
        "seconds": round(time.perf_counter() - started, 1),
    }
    BOARD.parent.mkdir(parents=True, exist_ok=True)
    with BOARD.open("a") as f:
        f.write(json.dumps(row, default=float) + "\n")
    s = score(row, baseline_for(row, load_board()))
    print(json.dumps({"name": args.name, "score": s, **row["metrics"]}, default=float))
    return 0


def cmd_show(args: argparse.Namespace) -> int:
    rows = load_board()
    scored = [(score(r, baseline_for(r, rows)) or 0.0, r) for r in rows]
    scored.sort(key=lambda sr: -sr[0])
    cols = [
        "ml_ndcg",
        "ml_hit",
        "ml_ndcg_full",
        "ml_cold_ndcg",
        "ml_group",
        "ml_similar",
        "p_ndcg",
        "p_hit",
        "p_group",
        "p_similar",
    ]
    print("| # | name | score | " + " | ".join(cols) + " | note |")
    print("|---|---|---|" + "---|" * len(cols) + "---|")
    for i, (s, r) in enumerate(scored[: args.top], 1):
        vals = " | ".join(f"{r['metrics'][c]:.4f}" for c in cols)
        flag = " (quick)" if r.get("quick") else ""
        print(f"| {i} | {r['name']}{flag} | {s:.3f} | {vals} | {r.get('note') or ''} |")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m eval.experiment")
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--name", required=True)
    r.add_argument("--recommender", default="app.services.recommender:get_recommender")
    r.add_argument("--overrides", default="")
    r.add_argument("--note", default="")
    r.add_argument("--quick", action="store_true", help="200 MovieLens users, 40 groups/method")
    r.add_argument("--seed", type=int, default=42, help="groups, sampling, persona hold-out")
    r.add_argument("--test-fraction", type=float, default=0.2, help="share of ratings held out")
    r.add_argument(
        "--pool-size",
        type=int,
        default=POOL_SIZE,
        help="candidates per user/group (core-api POOL_SIZE); scored against the default baseline",
    )
    r.add_argument(
        "--data",
        default=None,
        help="MovieLens folder (default ml-latest-small); e.g. eval/data/ml-32m-u3000-s1",
    )
    s = sub.add_parser("show")
    s.add_argument("--top", type=int, default=30)
    args = p.parse_args(argv)
    return cmd_run(args) if args.cmd == "run" else cmd_show(args)


if __name__ == "__main__":
    raise SystemExit(main())
