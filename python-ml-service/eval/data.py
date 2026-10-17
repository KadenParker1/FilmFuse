"""MovieLens `ml-latest-small` loader, mapped into FilmFuse's id/score space.

* movie ids: MovieLens `movieId` -> TMDB id via `links.csv`. Movies without a TMDB id are
  dropped. If several MovieLens ids map to one TMDB id, the first one in `links.csv` wins
  and ratings of the others are folded into it (per user, the latest rating is kept).
* scores: MovieLens 0.5-5.0 stars -> FilmFuse 1-10 integers via `round(stars * 2)`,
  clamped to [1, 10].
* genres: MovieLens genre names -> TMDB genre ids (the ids in
  `app.services.recommender.GENRE_IDS`) via `MOVIELENS_TO_TMDB_GENRE` below. Unmappable
  genres are ignored.

MovieLens genre      -> TMDB genre (id)
-------------------- -------------------------------------------------------------
Action               -> Action (28)
Adventure            -> Adventure (12)
Animation            -> Animation (16)
Children             -> Family (10751)
Comedy               -> Comedy (35)
Crime                -> Crime (80)
Documentary          -> Documentary (99)
Drama                -> Drama (18)
Fantasy              -> Fantasy (14)
Film-Noir            -> Crime (80)       (TMDB has no noir genre; noirs are tagged Crime)
Horror               -> Horror (27)
Musical              -> Music (10402)
Mystery              -> Mystery (9648)
Romance              -> Romance (10749)
Sci-Fi               -> Science Fiction (878)
Thriller             -> Thriller (53)
War                  -> War (10752)
Western              -> Western (37)
IMAX                 -> ignored (a format, not a genre)
(no genres listed)   -> ignored
TMDB History (36) and TV Movie (10770) have no MovieLens equivalent and never appear.
"""

from __future__ import annotations

import csv
import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path
from typing import TextIO

from app.services.recommender import GENRE_IDS

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
assert set(MOVIELENS_TO_TMDB_GENRE.values()) <= set(GENRE_IDS)

EXPECTED_FILES = ("ratings.csv", "movies.csv", "links.csv", "tags.csv")
DEFAULT_DATA_DIR = Path(__file__).resolve().parent / "data" / "ml-latest-small"

_YEAR_RE = re.compile(r"\((\d{4})\)\s*$")


@dataclass(frozen=True)
class Movie:
    tmdb_id: int
    movielens_id: int
    title: str
    year: int | None
    genre_ids: tuple[int, ...]


@dataclass(frozen=True)
class RatingEvent:
    user_id: int  # MovieLens userId
    movie_id: int  # TMDB id
    score: int  # 1-10
    timestamp: int  # unix seconds


@dataclass
class Dataset:
    movies: dict[int, Movie]  # keyed by TMDB id
    ratings: list[RatingEvent] = field(default_factory=list)

    @property
    def user_ids(self) -> list[int]:
        return sorted({r.user_id for r in self.ratings})


def map_score(stars: float) -> int:
    """MovieLens 0.5-5.0 stars -> FilmFuse 1-10 integer."""
    return max(1, min(10, round(stars * 2)))


def map_genres(genres_field: str) -> tuple[int, ...]:
    """'Action|Film-Noir|IMAX' -> TMDB genre ids, deduped, in first-seen order."""
    out: list[int] = []
    for name in genres_field.split("|"):
        gid = MOVIELENS_TO_TMDB_GENRE.get(name.strip())
        if gid is not None and gid not in out:
            out.append(gid)
    return tuple(out)


def parse_title_year(title: str) -> tuple[str, int | None]:
    m = _YEAR_RE.search(title)
    if not m:
        return title.strip(), None
    return title[: m.start()].strip(), int(m.group(1))


def parse_links(f: TextIO) -> dict[int, int]:
    """links.csv -> {movielens_id: tmdb_id}; rows without a TMDB id are skipped."""
    out: dict[int, int] = {}
    for row in csv.DictReader(f):
        tmdb = (row.get("tmdbId") or "").strip()
        if not tmdb:
            continue
        tmdb_id = int(float(tmdb))
        if tmdb_id >= 1:
            out[int(row["movieId"])] = tmdb_id
    return out


def canonical_ids(ml_to_tmdb: dict[int, int]) -> dict[int, int]:
    """Collapse duplicate TMDB ids: keep the first MovieLens id seen for each TMDB id."""
    seen: dict[int, int] = {}
    for ml_id, tmdb_id in ml_to_tmdb.items():
        seen.setdefault(tmdb_id, ml_id)
    return seen  # {tmdb_id: canonical movielens_id}


def parse_movies(f: TextIO, ml_to_tmdb: dict[int, int]) -> dict[int, Movie]:
    canonical = canonical_ids(ml_to_tmdb)
    movies: dict[int, Movie] = {}
    for row in csv.DictReader(f):
        ml_id = int(row["movieId"])
        tmdb_id = ml_to_tmdb.get(ml_id)
        if tmdb_id is None or canonical[tmdb_id] != ml_id:
            continue
        title, year = parse_title_year(row["title"])
        movies[tmdb_id] = Movie(tmdb_id, ml_id, title, year, map_genres(row["genres"]))
    return movies


def parse_ratings(f: TextIO, ml_to_tmdb: dict[int, int]) -> list[RatingEvent]:
    """ratings.csv -> RatingEvents in TMDB/1-10 space. Per (user, TMDB id) the latest wins."""
    latest: dict[tuple[int, int], RatingEvent] = {}
    for row in csv.DictReader(f):
        tmdb_id = ml_to_tmdb.get(int(row["movieId"]))
        if tmdb_id is None:
            continue
        ev = RatingEvent(
            user_id=int(row["userId"]),
            movie_id=tmdb_id,
            score=map_score(float(row["rating"])),
            timestamp=int(row["timestamp"]),
        )
        key = (ev.user_id, tmdb_id)
        prev = latest.get(key)
        if prev is None or ev.timestamp >= prev.timestamp:
            latest[key] = ev
    return sorted(latest.values(), key=lambda r: (r.user_id, r.timestamp, r.movie_id))


def build_dataset(links: TextIO, movies: TextIO, ratings: TextIO) -> Dataset:
    ml_to_tmdb = parse_links(links)
    movie_map = parse_movies(movies, ml_to_tmdb)
    events = [r for r in parse_ratings(ratings, ml_to_tmdb) if r.movie_id in movie_map]
    return Dataset(movies=movie_map, ratings=events)


def load_movielens(data_dir: Path | str = DEFAULT_DATA_DIR) -> Dataset:
    data_dir = Path(data_dir)
    missing = [n for n in ("ratings.csv", "movies.csv", "links.csv") if not (data_dir / n).exists()]
    if missing:
        raise FileNotFoundError(
            f"{data_dir} is missing {missing}. Run: python -m eval.download_movielens"
        )
    with (
        open(data_dir / "links.csv", newline="", encoding="utf-8") as links,
        open(data_dir / "movies.csv", newline="", encoding="utf-8") as movies,
        open(data_dir / "ratings.csv", newline="", encoding="utf-8") as ratings,
    ):
        return build_dataset(links, movies, ratings)


def group_by_user(ratings: Iterable[RatingEvent]) -> dict[int, list[RatingEvent]]:
    out: dict[int, list[RatingEvent]] = {}
    for r in ratings:
        out.setdefault(r.user_id, []).append(r)
    return out
