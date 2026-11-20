"""Draw a fixed random sample of users from a large MovieLens folder (e.g. ml-32m).

    python -m eval.subsample --source eval/data/ml-32m --users 3000 --seed 1

Writes eval/data/<source>-u<users>-s<seed>/ with the same three CSVs (ratings of the sampled
users only; movies.csv and links.csv copied), so `eval.data.load_movielens` and the harness load
it like ml-latest-small. Sampling: users whose seeded hash ranks lowest, so the same seed always
gives the same users, and different seeds give (almost) disjoint samples. Only users with at
least MIN_RATINGS ratings are eligible (the harness needs a history to split).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import shutil
from collections import Counter
from pathlib import Path

MIN_RATINGS = 20


def _rank(seed: int, user: str) -> int:
    return int.from_bytes(hashlib.sha256(f"{seed}:{user}".encode()).digest()[:8], "big")


def subsample(source: Path, users: int, seed: int) -> Path:
    target = source.parent / f"{source.name}-u{users}-s{seed}"
    target.mkdir(parents=True, exist_ok=True)
    counts: Counter[str] = Counter()
    with (source / "ratings.csv").open(newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        next(reader)
        for row in reader:
            counts[row[0]] += 1
    eligible = [u for u, c in counts.items() if c >= MIN_RATINGS]
    chosen = set(sorted(eligible, key=lambda u: _rank(seed, u))[:users])
    kept = 0
    with (
        (source / "ratings.csv").open(newline="", encoding="utf-8") as f,
        (target / "ratings.csv").open("w", newline="", encoding="utf-8") as out,
    ):
        reader = csv.reader(f)
        writer = csv.writer(out)
        writer.writerow(next(reader))
        for row in reader:
            if row[0] in chosen:
                writer.writerow(row)
                kept += 1
    for name in ("movies.csv", "links.csv"):
        shutil.copyfile(source / name, target / name)
    print(f"{target}: {len(chosen)} users, {kept} ratings (of {len(eligible)} eligible users)")
    return target


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--users", type=int, default=3000)
    p.add_argument("--seed", type=int, default=1)
    args = p.parse_args(argv)
    subsample(args.source, args.users, args.seed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
