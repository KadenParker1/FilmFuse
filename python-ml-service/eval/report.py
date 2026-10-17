"""Render evaluation results as Markdown (+ NaN-safe JSON)."""

from __future__ import annotations

import json
import math
from typing import Any

_INDIVIDUAL_HELP = {
    "precision": "share of the top-k that the user rated >= threshold in test",
    "recall": "share of the user's relevant test items found in the top-k",
    "ndcg": "rank-aware hit quality (1 = all relevant items at the very top)",
    "hit_rate": "share of users with at least one relevant item in the top-k",
    "catalog_coverage": "share of the catalog recommended to anyone",
    "mean_popularity_rank": "mean train-popularity rank of recs (1 = most rated; lower = "
    "more popularity-biased)",
}


def _fmt(v: Any) -> str:
    if isinstance(v, float):
        if math.isnan(v):
            return "n/a"
        return f"{v:.1f}" if abs(v) >= 100 else f"{v:.4f}"
    return str(v)


def _table(header: list[str], rows: list[list[Any]]) -> list[str]:
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(_fmt(c) for c in row) + " |" for row in rows]
    return out


def render_markdown(result: dict[str, Any]) -> str:
    cfg = result["config"]
    runs = result["recommenders"]
    k = cfg["k"]
    lines = [
        "# FilmFuse offline evaluation",
        "",
        f"Generated {result['generated_at']} in {result['elapsed_seconds']:.1f}s. "
        f"Evaluated: `{result['target']}`.",
        "",
        "## Setup",
        "",
        f"- Dataset: {result['dataset']['name']} — {result['dataset']['n_movies']} movies "
        f"(with TMDB ids), {result['dataset']['n_ratings']} ratings, "
        f"{result['dataset']['n_users_after_split']} users with >= {cfg['min_ratings']} ratings "
        f"({result['dataset']['n_eligible_users']} with >= 1 relevant test item).",
        f"- Split: per-user temporal, last {cfg['test_fraction']:.0%} of each user's ratings "
        f"= test. Relevant = test rating >= {cfg['relevance_threshold']}/10.",
        f"- k = {k}, seed = {cfg['seed']}, {cfg['n_users']} users sampled, "
        f"{cfg['n_groups']} groups per method (size {cfg['min_group_size']}-"
        f"{cfg['max_group_size']}), off-diagonal closeness = {cfg['closeness']}.",
        "- Candidates = every movie not rated in train (by the user / any group member), "
        "shuffled with a fixed seed. Baselines are trivial reference points, not the algorithm.",
        "",
        "## Individual recommendations",
        "",
    ]
    keys = [f"precision@{k}", f"recall@{k}", f"ndcg@{k}", f"hit_rate@{k}"]
    keys += ["catalog_coverage", "mean_popularity_rank"]
    lines += _table(
        ["recommender", *keys], [[r["name"], *(r["individual"][c] for c in keys)] for r in runs]
    )
    lines += [
        "",
        f"Catalog size {result['dataset']['n_movies']}; a random list has mean "
        f"popularity rank ~{result['dataset']['n_movies'] / 2:.0f}.",
        "",
    ]
    lines += [f"- **{name}**: {text}" for name, text in _INDIVIDUAL_HELP.items()]

    lines += ["", "## Groups", ""]
    gs = result["group_construction"]
    if gs:
        lines += _table(
            ["method", "groups", "mean size", "mean pairwise cosine"],
            [[m, s["n_groups"], s["mean_size"], s["mean_pairwise_cosine"]] for m, s in gs.items()],
        )
        lines += [
            "",
            "Satisfaction = each member's NDCG@k of the shared list on their OWN test items. "
            "mean = average member, min = least-happy member (least misery), std = spread "
            "(lower = fairer), all-hit = % of groups where every member got >= 1 relevant item.",
            "",
        ]
        gk = [f"mean_satisfaction@{k}", f"min_satisfaction@{k}", f"satisfaction_std@{k}"]
        for method in gs:
            lines += [f"### {method} groups", ""]
            rows = []
            for r in runs:
                for strategy, m in r["group"].get(method, {}).items():
                    rows.append(
                        [r["name"], strategy, *(m[c] for c in gk), m["pct_groups_all_members_hit"]]
                    )
            lines += _table(
                ["recommender", "strategy", "mean sat", "min sat", "sat std", "% all-hit"], rows
            )
            lines.append("")
    else:
        lines += ["(group evaluation disabled)", ""]

    lines += ["## Contract violations and timing", ""]
    lines += _table(
        ["recommender", "violations", "total s", "ms / user call", "ms / group call"],
        [
            [
                r["name"],
                ", ".join(f"{a}={b}" for a, b in r["contract_violations"].items()) or "none",
                r["timing"]["total_seconds"],
                r["timing"]["mean_user_call_ms"],
                r["timing"]["mean_group_call_ms"],
            ]
            for r in runs
        ],
    )
    lines.append("")
    return "\n".join(lines)


def _nan_to_none(obj: Any) -> Any:
    if isinstance(obj, float) and math.isnan(obj):
        return None
    if isinstance(obj, dict):
        return {k: _nan_to_none(v) for k, v in obj.items()}
    if isinstance(obj, list | tuple):
        return [_nan_to_none(v) for v in obj]
    return obj


def to_json(result: dict[str, Any]) -> str:
    return json.dumps(_nan_to_none(result), indent=2, allow_nan=False)
