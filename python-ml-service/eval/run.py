"""CLI: evaluate a recommender against the random and popularity baselines.

    python -m eval.run --recommender app.services.recommender:get_recommender \
        --k 10 --users 200 --groups 100 --seed 42 --out eval/reports/<timestamp>.md

Writes the Markdown report to --out and the full metrics as JSON next to it (same name,
.json). See the README section "Evaluating your recommender".
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from app.models.contract import Strategy
from eval.baselines import PopularityBaseline, RandomBaseline
from eval.data import DEFAULT_DATA_DIR, load_movielens
from eval.harness import EvalConfig, build_context, evaluate, load_recommender
from eval.report import render_markdown, to_json

REPORTS_DIR = Path(__file__).resolve().parent / "reports"


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m eval.run", description=__doc__.splitlines()[0])
    p.add_argument("--recommender", default="app.services.recommender:get_recommender")
    p.add_argument("--k", type=int, default=10)
    p.add_argument("--users", type=int, default=200)
    p.add_argument("--groups", type=int, default=100, help="groups per construction method")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--relevance-threshold", type=int, default=8)
    p.add_argument("--test-fraction", type=float, default=0.2)
    p.add_argument("--min-ratings", type=int, default=20)
    p.add_argument("--min-group-size", type=int, default=2)
    p.add_argument("--max-group-size", type=int, default=6)
    p.add_argument("--closeness", type=float, default=0.5)
    p.add_argument(
        "--strategies", default=",".join(s.value for s in Strategy), help="comma-separated"
    )
    p.add_argument("--data-dir", type=Path, default=DEFAULT_DATA_DIR)
    p.add_argument("--out", type=Path, default=None, help="Markdown report path")
    p.add_argument("--no-baselines", action="store_true")
    args = p.parse_args(argv)
    if not 1 <= args.k <= 100:
        p.error("--k must be between 1 and 100 (contract limit)")
    if not 1 <= args.relevance_threshold <= 10:
        p.error("--relevance-threshold must be between 1 and 10")
    if not 2 <= args.min_group_size <= args.max_group_size:
        p.error("need 2 <= --min-group-size <= --max-group-size")
    return args


def run(args: argparse.Namespace) -> dict[str, Any]:
    started = time.perf_counter()
    config = EvalConfig(
        k=args.k,
        n_users=args.users,
        n_groups=args.groups,
        seed=args.seed,
        relevance_threshold=args.relevance_threshold,
        test_fraction=args.test_fraction,
        min_ratings=args.min_ratings,
        min_group_size=args.min_group_size,
        max_group_size=args.max_group_size,
        closeness=args.closeness,
        strategies=[Strategy(s.strip()) for s in args.strategies.split(",") if s.strip()],
    )
    dataset = load_movielens(args.data_dir)
    ctx = build_context(dataset, config)

    candidates: list[tuple[str, Any]] = [(args.recommender, load_recommender(args.recommender))]
    if not args.no_baselines:
        train_events = [r for ev in ctx.split.train.values() for r in ev]
        candidates += [
            ("baseline:random", RandomBaseline(seed=args.seed)),
            ("baseline:popularity", PopularityBaseline(train_events)),
        ]
    runs = []
    for name, rec in candidates:
        print(f"evaluating {name} ...", file=sys.stderr)
        runs.append(evaluate(rec, ctx, name))

    return {
        "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "elapsed_seconds": time.perf_counter() - started,
        "target": args.recommender,
        "config": {
            "k": config.k,
            "n_users": len(ctx.users),
            "n_groups": config.n_groups,
            "seed": config.seed,
            "relevance_threshold": config.relevance_threshold,
            "test_fraction": config.test_fraction,
            "min_ratings": config.min_ratings,
            "min_group_size": config.min_group_size,
            "max_group_size": config.max_group_size,
            "closeness": config.closeness,
            "strategies": [s.value for s in config.strategies],
        },
        "dataset": {
            "name": Path(args.data_dir).name,
            "n_movies": len(dataset.movies),
            "n_ratings": len(dataset.ratings),
            "n_users_after_split": len(ctx.split.train),
            "n_eligible_users": len(ctx.relevant),
        },
        "group_construction": ctx.group_stats,
        "recommenders": runs,
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    out: Path = args.out or REPORTS_DIR / f"{datetime.now(UTC):%Y%m%dT%H%M%SZ}.md"
    result = run(args)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render_markdown(result), encoding="utf-8")
    out.with_suffix(".json").write_text(to_json(result), encoding="utf-8")
    print(f"wrote {out} and {out.with_suffix('.json')}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
