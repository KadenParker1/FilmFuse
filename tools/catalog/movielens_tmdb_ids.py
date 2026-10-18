#!/usr/bin/env python3
"""Write the TMDB ids from a MovieLens links.csv as a catalog-seeder id file.

MovieLens' links.csv maps movieId -> imdbId, tmdbId; some rows have no tmdbId. This writes each
unique, non-empty tmdbId once (in file order), one per line, for core-api's catalog seeder
(filmfuse.catalog.seed.id-file, see CLAUDE.md). The output is generated data: it lands in the
gitignored tools/catalog/out/ by default and must not be committed.

    python3 tools/catalog/movielens_tmdb_ids.py [links.csv] [-o out.txt]

The default input is python-ml-service/eval/data/ml-latest-small/links.csv (fetched by
python-ml-service/eval/download_movielens.py). Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections.abc import Iterable
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_LINKS = REPO / "python-ml-service" / "eval" / "data" / "ml-latest-small" / "links.csv"
DEFAULT_OUT = Path(__file__).resolve().parent / "out" / "movielens-tmdb-ids.txt"


def tmdb_ids(rows: Iterable[dict[str, str]]) -> list[int]:
    """Unique positive tmdbIds in first-seen order; blank or malformed values are skipped."""
    seen: set[int] = set()
    ids: list[int] = []
    for row in rows:
        raw = (row.get("tmdbId") or "").strip()
        if not raw:
            continue
        try:
            value = int(float(raw)) if "." in raw else int(raw)
        except ValueError:
            continue
        if value > 0 and value not in seen:
            seen.add(value)
            ids.append(value)
    return ids


def read_links(path: Path) -> list[int]:
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or "tmdbId" not in reader.fieldnames:
            raise ValueError(f"{path} has no tmdbId column (is it MovieLens links.csv?)")
        return tmdb_ids(reader)


def write_ids(ids: list[int], out: Path, source: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as handle:
        handle.write(
            f"# TMDB ids from {source.name} ({len(ids)} movies); generated, do not commit\n"
        )
        for value in ids:
            handle.write(f"{value}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "links", nargs="?", type=Path, default=DEFAULT_LINKS, help="MovieLens links.csv"
    )
    parser.add_argument("-o", "--output", type=Path, default=DEFAULT_OUT, help="id file to write")
    args = parser.parse_args(argv)
    if not args.links.is_file():
        print(f"links.csv not found: {args.links}", file=sys.stderr)
        return 2
    try:
        ids = read_links(args.links)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    write_ids(ids, args.output, args.links)
    print(f"Wrote {len(ids)} TMDB ids to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
