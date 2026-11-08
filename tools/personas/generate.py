#!/usr/bin/env python3
"""Generate deterministic SYNTHETIC rating histories for the test personas (stdlib only).

    python3 -I tools/personas/generate.py [--personas FILE] [--movielens DIR] [--out FILE]

This is TEST-DATA GENERATION, not a recommender. It invents plausible "watched and rated"
histories so seeded accounts look lived-in; it never ranks anything for a user, and nothing in
core-api or the ML service uses it. The project owner's recommendation algorithm is untouched.

For each persona it picks `volume` extra movies (none 0, light 10, medium 40, heavy 120,
power 300) from MovieLens ml-latest-small and gives each one a 1-10 score:

* Candidates: MovieLens movies with a TMDB id (links.csv), genres mapped to TMDB genre ids with
  the table from python-ml-service/eval/data.py. Popularity = number of rows in ratings.csv.
* Selection weight = taste x popularity x era, sampled without replacement (Efraimidis-Spirakis):
  - taste: exp(0.6 * (affinity - 5)), plus a floor of OFF_TASTE x the mean taste weight so some
    off-taste watches slip in (people mostly watch what they expect to like, but not only that);
  - popularity: popularity percentile p (1 = most rated), mainstream exp(4p), mixed exp(p),
    niche exp(-3p);
  - era (optional, soft): 1 inside [from, to], decaying by exp(-years outside / 10), min 0.15;
    unknown year 0.5.
* Score = round(5 + (affinity - 5) x min(1, spread) + rater.bias + gauss(0, 1.5 x spread)),
  clamped to 1-10, where affinity = mean of the persona's genre scores for the movie's genres
  (genres the persona didn't score count as neutral 5; a movie without genres is 5). A flat
  rater (spread < 1) has both less noise and a compressed taste signal.
* Seeded by the persona key (sha256), so the same inputs always give the same output.
* Favourites and anchors are never included; no movie appears twice.

Output: tools/personas/out/ratings.generated.json = {personaKey: [{tmdbId, title, year, score}]}
(gitignored).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import math
import random
import re
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common  # noqa: E402

# Copied from python-ml-service/eval/data.py (MOVIELENS_TO_TMDB_GENRE) - keep in step.
MOVIELENS_TO_TMDB_GENRE: dict[str, int] = {
    "Action": 28,
    "Adventure": 12,
    "Animation": 16,
    "Children": 10751,
    "Comedy": 35,
    "Crime": 80,
    "Documentary": 99,
    "Drama": 18,
    "Fantasy": 14,
    "Film-Noir": 80,
    "Horror": 27,
    "Musical": 10402,
    "Mystery": 9648,
    "Romance": 10749,
    "Sci-Fi": 878,
    "Thriller": 53,
    "War": 10752,
    "Western": 37,
}

TASTE_SHARPNESS = 0.6
OFF_TASTE = 0.25
POPULARITY_EXPONENT = {"mainstream": 4.0, "mixed": 1.0, "niche": -3.0}
ERA_DECAY_YEARS = 10.0
ERA_FLOOR = 0.15
NOISE_SD = 1.5
SEED_SALT = "filmfuse-personas-v1"

_YEAR_RE = re.compile(r"\((\d{4})\)\s*$")


@dataclass(frozen=True)
class Candidate:
    tmdb_id: int
    title: str
    year: int | None
    genre_ids: tuple[int, ...]
    popularity: float  # percentile of rating count, 0 (least rated) .. 1 (most rated)


def map_genres(field: str) -> tuple[int, ...]:
    out: list[int] = []
    for name in field.split("|"):
        gid = MOVIELENS_TO_TMDB_GENRE.get(name.strip())
        if gid is not None and gid not in out:
            out.append(gid)
    return tuple(out)


def parse_title(raw: str) -> tuple[str, int | None]:
    m = _YEAR_RE.search(raw)
    if not m:
        return raw.strip(), None
    title = raw[: m.start()].strip()
    # MovieLens moves leading articles: "Matrix, The" -> "The Matrix".
    am = re.match(r"^(.*), (The|A|An)$", title)
    if am:
        title = f"{am.group(2)} {am.group(1)}"
    return title, int(m.group(1))


def load_movielens(directory: Path | str) -> list[Candidate]:
    directory = Path(directory)
    missing = [
        n for n in ("movies.csv", "links.csv", "ratings.csv") if not (directory / n).exists()
    ]
    if missing:
        raise common.PersonaFileError(
            f"{directory} is missing {missing}. Fetch MovieLens with: "
            "cd python-ml-service && .venv/bin/python -m eval.download_movielens"
        )
    links: dict[int, int] = {}
    with open(directory / "links.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            raw = (row.get("tmdbId") or "").strip()
            if raw:
                tmdb = int(float(raw))
                if tmdb > 0:
                    links[int(row["movieId"])] = tmdb
    counts: dict[int, int] = {}
    with open(directory / "ratings.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ml = int(row["movieId"])
            counts[ml] = counts.get(ml, 0) + 1
    rows: list[tuple[int, str, int | None, tuple[int, ...], int]] = []
    seen: set[int] = set()
    with open(directory / "movies.csv", newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ml = int(row["movieId"])
            tmdb = links.get(ml)
            if tmdb is None or tmdb in seen:  # first MovieLens id per TMDB id wins
                continue
            seen.add(tmdb)
            title, year = parse_title(row["title"])
            rows.append((tmdb, title, year, map_genres(row["genres"]), counts.get(ml, 0)))
    # Popularity percentile by rating count (ties share the lowest rank; stable for determinism).
    ordered = sorted(c for *_, c in rows)
    n = len(ordered)
    rank_of: dict[int, int] = {}
    for i, c in enumerate(ordered):
        rank_of.setdefault(c, i)
    out = [
        Candidate(tmdb, title, year, genres, (rank_of[count] / (n - 1)) if n > 1 else 1.0)
        for tmdb, title, year, genres, count in rows
    ]
    out.sort(key=lambda c: c.tmdb_id)
    return out


def seed_for(key: str, salt: str = SEED_SALT) -> int:
    return int.from_bytes(hashlib.sha256(f"{salt}:{key}".encode()).digest()[:8], "big")


def score_affinity(genres: dict, genre_ids: tuple[int, ...]) -> float:
    """Mean of stated genre scores for the movie's genres (unknown genre = 5; no genres = 5)."""
    return common.stated_affinity(genres, genre_ids)


def synthetic_score(affinity: float, bias: float, spread: float, noise: float) -> int:
    """affinity's distance from neutral, compressed for flat raters (spread < 1), + bias + noise.

    `noise` is a standard-normal draw; it is scaled by NOISE_SD x spread.
    """
    centred = common.NEUTRAL + (affinity - common.NEUTRAL) * min(1.0, spread)
    return clamp_round(centred + bias + noise * NOISE_SD * spread)


def era_weight(era: dict | None, year: int | None) -> float:
    if not era:
        return 1.0
    if year is None:
        return 0.5
    lo = int(era.get("from", -10_000))
    hi = int(era.get("to", 10_000))
    outside = lo - year if year < lo else (year - hi if year > hi else 0)
    return max(ERA_FLOOR, math.exp(-outside / ERA_DECAY_YEARS))


def popularity_weight(popularity: str | None, percentile: float) -> float:
    return math.exp(POPULARITY_EXPONENT.get(popularity or "mixed", 1.0) * percentile)


def clamp_round(x: float) -> int:
    return max(1, min(10, int(math.floor(x + 0.5))))


def generate_for(persona: dict, candidates: list[Candidate], salt: str = SEED_SALT) -> list[dict]:
    count = common.VOLUMES.get(persona.get("volume", "none"), 0)
    if count <= 0:
        return []
    rng = random.Random(seed_for(persona["key"], salt))
    genres = persona.get("genres", {})
    reserved = common.reserved_ids(persona)
    pool = [c for c in candidates if c.tmdb_id not in reserved]
    if not pool:
        return []
    affinities = [score_affinity(genres, c.genre_ids) for c in pool]
    taste = [math.exp(TASTE_SHARPNESS * (a - common.NEUTRAL)) for a in affinities]
    floor = OFF_TASTE * (sum(taste) / len(taste))
    keyed: list[tuple[float, int]] = []
    for i, c in enumerate(pool):
        w = (taste[i] + floor) * popularity_weight(persona.get("popularity"), c.popularity)
        w *= era_weight(persona.get("era"), c.year)
        u = rng.random() or 1e-12
        keyed.append((math.log(u) / w, i))  # Efraimidis-Spirakis: top-k of u^(1/w)
    keyed.sort(key=lambda t: (-t[0], pool[t[1]].tmdb_id))
    rater = persona.get("rater") or {}
    bias = float(rater.get("bias", 0.0))
    spread = float(rater.get("spread", 1.0))
    out: list[dict] = []
    for _, i in keyed[:count]:
        c = pool[i]
        score = synthetic_score(affinities[i], bias, spread, rng.gauss(0.0, 1.0))
        out.append({"tmdbId": c.tmdb_id, "title": c.title, "year": c.year, "score": score})
    return out


def generate(data: dict, candidates: list[Candidate], keys: list[str] | None = None) -> dict:
    return {p["key"]: generate_for(p, candidates) for p in common.select(data, keys)}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Generate deterministic SYNTHETIC rating histories for the test personas "
        "(test data, not a recommender).",
    )
    ap.add_argument(
        "--personas",
        type=Path,
        default=common.DEFAULT_PERSONAS,
        help="resolved persona file (default: personas.resolved.json)",
    )
    ap.add_argument(
        "--movielens",
        type=Path,
        default=common.DEFAULT_MOVIELENS,
        help="MovieLens ml-latest-small directory",
    )
    ap.add_argument(
        "--out",
        type=Path,
        default=common.DEFAULT_GENERATED,
        help="output JSON (default: out/ratings.generated.json)",
    )
    ap.add_argument("--keys", help="comma-separated persona keys (default: all)")
    args = ap.parse_args(argv)
    try:
        data = common.load_personas(args.personas)
        candidates = load_movielens(args.movielens)
        result = generate(data, candidates, common.parse_keys(args.keys))
    except common.PersonaFileError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    common.write_json(args.out, result)
    total = sum(len(v) for v in result.values())
    print(
        f"SYNTHETIC test data: {total} generated ratings for {len(result)} personas "
        f"from {len(candidates)} MovieLens movies -> {args.out}"
    )
    for key, rows in result.items():
        if rows:
            mean = sum(r["score"] for r in rows) / len(rows)
            print(f"  {key:28s} {len(rows):4d} ratings, mean {mean:.1f}")
        else:
            print(f"  {key:28s}    0 ratings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
