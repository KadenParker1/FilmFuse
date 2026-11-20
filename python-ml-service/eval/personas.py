"""Persona social-network harness: hold-out evaluation on the seeded test personas and groups.

    ML_DATABASE_URL=... python -m eval.personas [--recommender module:attr]

Unlike `tools/personas/sessions.py` (which scores lists against members' *stated* genres and can
be gamed by ignoring everything else), this hides part of each persona's ratings and checks
whether the model recommends the hidden favourites:

* per persona: ratings (minus favourites / least favourites, which are separate signals) are
  split at random (seeded): TEST_FRACTION held out, the rest is training data;
* the model is trained on TRAIN ratings only (`ContentRecommender.train_on`) over the real local
  catalog (TMDB genres, keywords, votes) read through ML_DATABASE_URL;
* candidates: like core-api's live pool, the POOL_POPULAR most popular movies of the whole local
  catalog (~74k TMDB movies) minus the member(s)' training items. Held-out movies outside that
  pool can't be recommended (as in production), so absolute numbers are low;
* relevant = held-out ratings >= 8; "misery" = held-out ratings <= 3;
* groups: the 40 groups in tools/personas/networks.json, with closeness from app.friend_closeness,
  reported by intent (easy = shared taste, pair, hard, big) and by MEASURED taste similarity
  (mean pairwise cosine of members' centred training ratings, split into terciles).

Synthetic personas: ratings were generated from stated genre scores plus noise, so this is a
sanity check alongside MovieLens, not a replacement for it.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from uuid import UUID

import numpy as np

from app.models.contract import (
    Favorite,
    GenrePref,
    GroupMember,
    GroupRecommendationRequest,
    MoodPref,
    Rating,
    Strategy,
    UserRecommendationRequest,
    UserVectorRequest,
)
from eval import metrics
from eval.harness import load_recommender

PERSONAS_DIR = Path(__file__).resolve().parents[2] / "tools" / "personas"
TEST_FRACTION = 0.2
MIN_RATINGS = 10
RELEVANT_MIN = 8
MISERY_MAX = 3
POOL_POPULAR = 1000  # core-api ContentFilterService.POOL_SIZE
STRATEGIES = [Strategy.MEAN, Strategy.LEAST_MISERY, Strategy.FAIRNESS, Strategy.CLOSENESS_WEIGHTED]


@dataclass
class Persona:
    key: str
    user_id: UUID
    genre_prefs: list[GenrePref] = field(default_factory=list)
    mood_prefs: list[MoodPref] = field(default_factory=list)
    favorites: list[Favorite] = field(default_factory=list)
    least_favorites: list[Favorite] = field(default_factory=list)
    train: list[tuple[int, int, float]] = field(default_factory=list)  # movie, score, ts
    test: dict[int, int] = field(default_factory=dict)  # movie -> score

    @property
    def relevant(self) -> set[int]:
        return {m for m, s in self.test.items() if s >= RELEVANT_MIN}

    @property
    def miserable(self) -> set[int]:
        return {m for m, s in self.test.items() if s <= MISERY_MAX}


@dataclass
class Group:
    name: str
    intent: str
    members: list[str]  # persona keys
    closeness: list[list[float]]
    similarity: float = 0.0
    tercile: str = ""


def _query(conn, sql: str, params: tuple = ()) -> list[tuple]:
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def load(url: str, seed: int = 42) -> tuple[dict[str, Persona], list[Group]]:
    import psycopg

    state = json.loads((PERSONAS_DIR / "out" / "seed-state.json").read_text())["personas"]
    networks = json.loads((PERSONAS_DIR / "networks.json").read_text())
    people = {k: Persona(k, UUID(v["userId"])) for k, v in state.items()}
    by_id = {p.user_id: p for p in people.values()}
    ids = [str(p.user_id) for p in people.values()]
    with psycopg.connect(url, connect_timeout=5) as conn:
        for uid, g, s in _query(
            conn,
            "select user_id, genre_id, score from app.user_genre_prefs"
            " where user_id = any(%s::uuid[])",
            (ids,),
        ):
            by_id[uid].genre_prefs.append(GenrePref(genre_id=g, score=s))
        for uid, mood, s in _query(
            conn,
            "select user_id, mood_tag, score from app.user_mood_prefs"
            " where user_id = any(%s::uuid[])",
            (ids,),
        ):
            by_id[uid].mood_prefs.append(MoodPref(mood_tag=mood, score=s))
        for table, attr in (
            ("user_favorite_movies", "favorites"),
            ("user_least_favorite_movies", "least_favorites"),
        ):
            for uid, m, rank in _query(
                conn,
                f"select user_id, movie_id, rank from app.{table} where user_id = any(%s::uuid[])",
                (ids,),
            ):
                getattr(by_id[uid], attr).append(Favorite(movie_id=m, rank=rank))
        ratings: dict[UUID, list[tuple[int, int, float]]] = defaultdict(list)
        for uid, m, s, ts in _query(
            conn,
            "select user_id, movie_id, score, extract(epoch from created_at) from app.ratings "
            "where user_id = any(%s::uuid[]) order by user_id, movie_id",
            (ids,),
        ):
            ratings[uid].append((m, int(s), float(ts)))
        close = {
            (a, b): float(score)
            for a, b, score in _query(
                conn,
                "select user_id, friend_id, score from app.friend_closeness"
                " where user_id = any(%s::uuid[])",
                (ids,),
            )
        }
    for p in people.values():
        signals = {f.movie_id for f in p.favorites + p.least_favorites}
        events = [e for e in ratings.get(p.user_id, []) if e[0] not in signals]
        rng = random.Random(f"{seed}:{p.key}")
        rng.shuffle(events)
        n_test = int(len(events) * TEST_FRACTION) if len(events) >= MIN_RATINGS else 0
        p.test = {m: s for m, s, _ in events[:n_test]}
        p.train = events[n_test:] + [e for e in ratings.get(p.user_id, []) if e[0] in signals]
    groups = []
    for g in networks["groups"]:
        # networks.json lists the creator separately from the other members.
        keys = list(dict.fromkeys([g.get("creator"), *g["members"]]))
        members = [m for m in keys if m in people]
        if len(members) < 2:
            continue
        uids = [people[m].user_id for m in members]
        mat = [
            [1.0 if i == j else close.get((a, b), 0.0) for j, b in enumerate(uids)]
            for i, a in enumerate(uids)
        ]
        groups.append(Group(g["name"], g.get("intent", "?"), members, mat))
    _measure_similarity(people, groups)
    return people, groups


def _measure_similarity(people: dict[str, Persona], groups: list[Group]) -> None:
    """Mean pairwise cosine of members' mean-centred training ratings; terciles low/mid/high."""

    def vec(p: Persona) -> dict[int, float]:
        if not p.train:
            return {}
        mean = sum(s for _, s, _ in p.train) / len(p.train)
        return {m: s - mean for m, s, _ in p.train}

    vecs = {k: vec(p) for k, p in people.items()}

    def cos(a: dict[int, float], b: dict[int, float]) -> float:
        common = set(a) & set(b)
        num = sum(a[m] * b[m] for m in common)
        na = np.sqrt(sum(v * v for v in a.values()))
        nb = np.sqrt(sum(v * v for v in b.values()))
        return float(num / (na * nb)) if na and nb else 0.0

    for g in groups:
        pairs = [(a, b) for i, a in enumerate(g.members) for b in g.members[i + 1 :]]
        g.similarity = float(np.mean([cos(vecs[a], vecs[b]) for a, b in pairs])) if pairs else 0.0
    ranked = sorted(groups, key=lambda g: g.similarity)
    for i, g in enumerate(ranked):
        g.tercile = ("dissimilar", "mixed", "similar")[min(2, 3 * i // max(1, len(ranked)))]


def _vector_request(p: Persona) -> UserVectorRequest:
    return UserVectorRequest(
        user_id=p.user_id,
        genre_prefs=p.genre_prefs,
        mood_prefs=p.mood_prefs,
        favorites=p.favorites,
        least_favorites=p.least_favorites,
        ratings=[
            Rating(
                movie_id=m,
                score=s,
                source="quick_rate",
                created_at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts)),
            )
            for m, s, ts in p.train
        ],
    )


def evaluate(rec: Any, people: dict[str, Persona], groups: list[Group], k: int = 10) -> dict:
    from app.services.catalog import PostgresCatalog  # noqa: F401  (type of rec.catalog)

    started = time.perf_counter()
    rec.train_on({p.user_id: p.train for p in people.values()})
    # Most popular first, like core-api (order by popularity desc); extra room for seen items.
    known = rec.catalog.get_many(rec.catalog.all_ids())
    by_pop = sorted(known.values(), key=lambda m: (-(m.popularity or 0.0), m.movie_id))
    universe = [m.movie_id for m in by_pop[: POOL_POPULAR + 600]]
    vectors = {key: rec.build_user_vector(_vector_request(p)).vector for key, p in people.items()}

    def pool(keys: list[str]) -> tuple[list[int], set[int]]:
        seen = {m for key in keys for m, _, _ in people[key].train}
        seen |= {
            f.movie_id for key in keys for f in people[key].favorites + people[key].least_favorites
        }
        rng = random.Random(",".join(sorted(keys)))
        cands = [m for m in universe if m not in seen][:POOL_POPULAR]
        rng.shuffle(cands)  # pool order must not leak anything
        return cands, seen

    ind = defaultdict(list)
    for key, p in people.items():
        if not p.relevant:
            continue
        cands, seen = pool([key])
        resp = rec.recommend_for_user(
            UserRecommendationRequest(
                user_id=p.user_id,
                vector=vectors[key],
                candidate_movie_ids=cands,
                exclude_movie_ids=sorted(seen),
                k=k,
            )
        )
        recs = [r.movie_id for r in resp.results]
        ind["ndcg"].append(metrics.ndcg_at_k(recs, p.relevant, k))
        ind["hit"].append(metrics.hit_rate_at_k(recs, p.relevant, k))
        ind["precision"].append(metrics.precision_at_k(recs, p.relevant, k))
        ind["misery"].append(sum(m in p.miserable for m in recs[:k]) / k)

    grp: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for g in groups:
        cands, seen = pool(g.members)
        rel = [people[m].relevant for m in g.members]
        for strategy in STRATEGIES:
            req = GroupRecommendationRequest(
                members=[
                    GroupMember(user_id=people[m].user_id, vector=vectors[m]) for m in g.members
                ],
                closeness=g.closeness,
                candidate_movie_ids=cands,
                exclude_movie_ids=sorted(seen),
                strategy=strategy,
                k=k,
            )
            recs = [r.movie_id for r in rec.recommend_for_group(req).results]
            row = metrics.group_summary(recs, rel, k)
            row["misery"] = float(
                np.mean([sum(m in people[x].miserable for m in recs[:k]) / k for x in g.members])
            )
            for bucket in ("all", f"intent:{g.intent}", f"taste:{g.tercile}"):
                grp[bucket][strategy.value].append(row)

    def summarise(rows: list[dict]) -> dict:
        return {
            "mean_sat": metrics.mean([r["mean_satisfaction"] for r in rows]),
            "min_sat": metrics.mean([r["min_satisfaction"] for r in rows]),
            "all_hit": metrics.mean([r["all_members_hit"] for r in rows]),
            "misery": metrics.mean([r["misery"] for r in rows]),
            "n": len(rows),
        }

    return {
        "individual": {f"{k_}@{k}": metrics.mean(v) for k_, v in ind.items()}
        | {"n_users": len(ind["ndcg"])},
        "group": {b: {s: summarise(rows) for s, rows in d.items()} for b, d in grp.items()},
        "group_similarity": {g.name: [g.intent, g.tercile, round(g.similarity, 3)] for g in groups},
        "seconds": time.perf_counter() - started,
    }


def make_recommender(spec: str, url: str) -> Any:
    from app.services.catalog import PostgresCatalog
    from app.services.social import load_friend_graph

    rec = load_recommender(spec)
    rec.catalog = PostgresCatalog(url, background_refresh=False)
    # Friend graph only (closeness, blocks, visibility); `train_on` builds the friend signal from
    # TRAIN ratings, so held-out ratings never reach it.
    rec.friend_graph = load_friend_graph(url)[0]
    return rec


def main(argv: list[str] | None = None) -> int:
    import os

    p = argparse.ArgumentParser(prog="python -m eval.personas")
    p.add_argument("--recommender", default="app.services.recommender:get_recommender")
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args(argv)
    url = os.environ.get("ML_DATABASE_URL")
    if not url:
        print(
            "ML_DATABASE_URL is required (read-only ml_reader on the seeded local stack)",
            file=sys.stderr,
        )
        return 2
    people, groups = load(url, args.seed)
    result = evaluate(make_recommender(args.recommender, url), people, groups)
    print(json.dumps(result, indent=2, default=float))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
