"""Evaluation loop: drives any `Recommender` through the contract and scores the results.

Optional hook (outside the frozen protocol): if the recommender object has a method
`prepare_for_eval(data: EvalTrainingData)`, it is called once before evaluation with the
movie catalog and every user's TRAIN ratings — never test ratings. Offline there is no
FilmFuse database, so this is how a content-based or collaborative recommender gets the
metadata / interactions it would normally read through `ML_DATABASE_URL`.
"""

from __future__ import annotations

import importlib
import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Any
from uuid import UUID

from app.models.contract import Strategy, UserVector
from app.services.recommender import Recommender
from eval import metrics
from eval.builders import (
    build_group_request,
    build_user_recommendation_request,
    build_user_vector_request,
    candidate_pool,
    user_uuid,
)
from eval.data import Dataset, Movie, RatingEvent
from eval.groups import METHODS, Group, cosine_matrix, make_groups, mean_pairwise_similarity
from eval.split import Split, eligible_users, relevant_items, sample_users, temporal_split


@dataclass
class EvalConfig:
    k: int = 10
    n_users: int = 200
    n_groups: int = 100  # per construction method
    seed: int = 42
    relevance_threshold: int = 8
    test_fraction: float = 0.2
    min_ratings: int = 20
    min_group_size: int = 2
    max_group_size: int = 6
    closeness: float = 0.5
    strategies: list[Strategy] = field(default_factory=lambda: list(Strategy))
    group_methods: tuple[str, ...] = METHODS


@dataclass
class EvalTrainingData:
    """Handed to an optional `prepare_for_eval` hook. TRAIN data only."""

    movies: dict[int, Movie]  # TMDB id -> metadata (title, year, TMDB genre ids)
    train: dict[UUID, list[RatingEvent]]  # contract user id -> train ratings (time order)


@dataclass
class EvalContext:
    config: EvalConfig
    dataset: Dataset
    split: Split
    users: list[int]  # sampled individual-eval users
    groups: list[Group]
    all_movie_ids: list[int]
    popularity_rank: dict[int, int]
    relevant: dict[int, set[int]]
    group_stats: dict[str, dict[str, float]]

    def training_data(self) -> EvalTrainingData:
        return EvalTrainingData(
            movies=self.dataset.movies,
            train={user_uuid(u): ev for u, ev in self.split.train.items()},
        )


def build_context(dataset: Dataset, config: EvalConfig) -> EvalContext:
    split = temporal_split(dataset.ratings, config.test_fraction, config.min_ratings)
    pool = eligible_users(split, config.relevance_threshold)
    users = sample_users(pool, config.n_users, config.seed)
    relevant = {u: relevant_items(split.test[u], config.relevance_threshold) for u in pool}

    train_events = [r for ev in split.train.values() for r in ev]
    counts = Counter(r.movie_id for r in train_events)
    all_movie_ids = sorted(dataset.movies)
    by_pop = sorted(all_movie_ids, key=lambda m: (-counts[m], m))
    popularity_rank = {m: i + 1 for i, m in enumerate(by_pop)}

    groups: list[Group] = []
    group_stats: dict[str, dict[str, float]] = {}
    if config.n_groups > 0 and config.group_methods:
        sim = cosine_matrix(pool, split.train)
        for method in config.group_methods:
            gs = make_groups(
                pool,
                sim,
                config.n_groups,
                method,
                config.seed,
                config.min_group_size,
                config.max_group_size,
            )
            groups.extend(gs)
            group_stats[method] = {
                "n_groups": len(gs),
                "mean_size": metrics.mean([len(g.members) for g in gs]),
                "mean_pairwise_cosine": metrics.mean(
                    [mean_pairwise_similarity(g, pool, sim) for g in gs]
                ),
            }
    return EvalContext(
        config, dataset, split, users, groups, all_movie_ids, popularity_rank, relevant, group_stats
    )


def load_recommender(spec: str) -> Recommender:
    """'pkg.module:attr' -> recommender. attr may be an instance, a class or a factory."""
    module_name, _, attr = spec.partition(":")
    if not attr:
        raise ValueError(f"expected 'module:attr', got {spec!r}")
    obj: Any = getattr(importlib.import_module(module_name), attr)
    if not hasattr(obj, "recommend_for_user") or isinstance(obj, type):
        obj = obj()
    return obj


class _Violations(Counter):
    def check(self, ids: list[int], candidates: set[int], exclude: set[int], k: int) -> list[int]:
        """Count contract problems; return the cleaned top-k used for scoring."""
        if len(ids) > k:
            self["over_k"] += 1
        out: list[int] = []
        seen: set[int] = set()
        for m in ids:
            if m in seen:
                self["duplicate_result"] += 1
            elif m in exclude:
                self["excluded_result"] += 1
            elif m not in candidates:
                self["result_not_in_candidates"] += 1
            else:
                seen.add(m)
                out.append(m)
        return out[:k]


def evaluate(rec: Recommender, ctx: EvalContext, name: str) -> dict[str, Any]:
    cfg = ctx.config
    k = cfg.k
    started = time.perf_counter()
    if hasattr(rec, "prepare_for_eval"):
        rec.prepare_for_eval(ctx.training_data())

    vectors: dict[int, UserVector] = {}

    def vector_for(u: int) -> UserVector:
        if u not in vectors:
            req = build_user_vector_request(u, ctx.split.train[u], ctx.dataset.movies)
            vectors[u] = rec.build_user_vector(req).vector
        return vectors[u]

    violations = _Violations()
    latencies: dict[str, list[float]] = {"user": [], "group": []}

    # ---- individual ------------------------------------------------------------
    per_user: dict[str, list[float]] = {"precision": [], "recall": [], "ndcg": [], "hit_rate": []}
    rec_lists: list[list[int]] = []
    for u in ctx.users:
        seen = {r.movie_id for r in ctx.split.train[u]}
        candidates = candidate_pool(ctx.all_movie_ids, seen, f"{cfg.seed}:user:{u}")
        req = build_user_recommendation_request(u, vector_for(u), candidates, seen, k)
        t0 = time.perf_counter()
        resp = rec.recommend_for_user(req)
        latencies["user"].append(time.perf_counter() - t0)
        recs = violations.check([r.movie_id for r in resp.results], set(candidates), seen, k)
        rel = ctx.relevant[u]
        rec_lists.append(recs)
        per_user["precision"].append(metrics.precision_at_k(recs, rel, k))
        per_user["recall"].append(metrics.recall_at_k(recs, rel, k))
        per_user["ndcg"].append(metrics.ndcg_at_k(recs, rel, k))
        per_user["hit_rate"].append(metrics.hit_rate_at_k(recs, rel, k))

    individual = {f"{key}@{k}": metrics.mean(v) for key, v in per_user.items()}
    individual["catalog_coverage"] = metrics.catalog_coverage(rec_lists, len(ctx.all_movie_ids), k)
    individual["mean_popularity_rank"] = metrics.mean_popularity_rank(
        rec_lists, ctx.popularity_rank, k
    )
    individual["n_users"] = len(ctx.users)

    # ---- groups ------------------------------------------------------------------
    raw: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for g in ctx.groups:
        seen = {r.movie_id for u in g.members for r in ctx.split.train[u]}
        candidates = candidate_pool(ctx.all_movie_ids, seen, f"{cfg.seed}:group:{g.group_id}")
        cand_set = set(candidates)
        members = [(u, vector_for(u)) for u in g.members]
        member_rel = [ctx.relevant[u] for u in g.members]
        member_ids = {user_uuid(u) for u in g.members}
        for strategy in cfg.strategies:
            req = build_group_request(members, candidates, seen, strategy, k, cfg.closeness)
            t0 = time.perf_counter()
            resp = rec.recommend_for_group(req)
            latencies["group"].append(time.perf_counter() - t0)
            if any(set(r.member_scores) != member_ids for r in resp.results):
                violations["member_scores_mismatch"] += 1
            recs = violations.check([r.movie_id for r in resp.results], cand_set, seen, k)
            raw.setdefault((g.method, strategy.value), []).append(
                metrics.group_summary(recs, member_rel, k)
            )

    group: dict[str, dict[str, dict[str, float]]] = {}
    for (method, strategy), rows in raw.items():
        group.setdefault(method, {})[strategy] = {
            f"mean_satisfaction@{k}": metrics.mean([r["mean_satisfaction"] for r in rows]),
            f"min_satisfaction@{k}": metrics.mean([r["min_satisfaction"] for r in rows]),
            f"satisfaction_std@{k}": metrics.mean([r["satisfaction_std"] for r in rows]),
            "pct_groups_all_members_hit": 100.0
            * metrics.mean([r["all_members_hit"] for r in rows]),
            "n_groups": len(rows),
        }

    return {
        "name": name,
        "model_version": getattr(rec, "model_version", type(rec).__name__),
        "individual": individual,
        "group": group,
        "contract_violations": dict(violations),
        "timing": {
            "total_seconds": time.perf_counter() - started,
            "mean_user_call_ms": 1000 * metrics.mean(latencies["user"]),
            "mean_group_call_ms": 1000 * metrics.mean(latencies["group"]),
        },
    }
