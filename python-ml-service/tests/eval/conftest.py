"""Tiny synthetic MovieLens-shaped dataset (no network)."""

import io
import random
from pathlib import Path

import pytest

from eval.data import Dataset, build_dataset

GENRE_CYCLE = ["Action|Adventure", "Comedy", "Drama|Romance", "Horror|Thriller", "Sci-Fi|IMAX"]
N_MOVIES = 40
N_USERS = 12
RATINGS_PER_USER = 25


def tiny_csvs() -> dict[str, str]:
    links = ["movieId,imdbId,tmdbId"]
    movies = ["movieId,title,genres"]
    for i in range(1, N_MOVIES + 1):
        links.append(f"{i},{100000 + i},{1000 + i}")
        movies.append(f'{i},"Movie {i} ({1990 + i % 20})",{GENRE_CYCLE[i % len(GENRE_CYCLE)]}')
    rng = random.Random(0)
    ratings = ["userId,movieId,rating,timestamp"]
    for u in range(1, N_USERS + 1):
        for j, m in enumerate(rng.sample(range(1, N_MOVIES + 1), RATINGS_PER_USER)):
            stars = rng.choice([2.0, 3.0, 3.5, 4.0, 4.5, 5.0])
            ratings.append(f"{u},{m},{stars},{1_600_000_000 + u * 10_000 + j}")
    return {
        "links.csv": "\n".join(links) + "\n",
        "movies.csv": "\n".join(movies) + "\n",
        "ratings.csv": "\n".join(ratings) + "\n",
        "tags.csv": "userId,movieId,tag,timestamp\n",
    }


@pytest.fixture
def tiny_dataset() -> Dataset:
    c = tiny_csvs()
    return build_dataset(
        io.StringIO(c["links.csv"]), io.StringIO(c["movies.csv"]), io.StringIO(c["ratings.csv"])
    )


@pytest.fixture
def tiny_data_dir(tmp_path: Path) -> Path:
    d = tmp_path / "ml-tiny"
    d.mkdir()
    for name, text in tiny_csvs().items():
        (d / name).write_text(text, encoding="utf-8")
    return d
