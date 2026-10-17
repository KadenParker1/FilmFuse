import io
from pathlib import Path

import pytest

from app.services.recommender import GENRE_IDS
from eval.data import (
    MOVIELENS_TO_TMDB_GENRE,
    build_dataset,
    load_movielens,
    map_genres,
    map_score,
    parse_links,
    parse_ratings,
    parse_title_year,
)

LINKS = """movieId,imdbId,tmdbId
1,0114709,862
2,0113497,8844
3,0113228,
4,0114885,862
5,0113041,11862
"""
MOVIES = """movieId,title,genres
1,Toy Story (1995),Adventure|Animation|Children|Comedy|Fantasy
2,Jumanji (1995),Adventure|Children|Fantasy
3,Grumpier Old Men (1995),Comedy|Romance
4,"Toy Story, Duplicate (1995)",Comedy
5,"Father of the Bride Part II",(no genres listed)
"""
RATINGS = """userId,movieId,rating,timestamp
1,1,4.0,964982703
1,2,0.5,964981247
1,3,5.0,964982224
1,4,2.5,964983000
2,1,3.5,964982000
"""


@pytest.mark.parametrize(
    ("stars", "score"),
    [(0.5, 1), (1.0, 2), (2.5, 5), (3.5, 7), (4.0, 8), (4.5, 9), (5.0, 10)],
)
def test_map_score(stars: float, score: int) -> None:
    assert map_score(stars) == score


def test_map_score_clamps() -> None:
    assert map_score(0.0) == 1
    assert map_score(6.0) == 10


def test_genre_mapping() -> None:
    assert map_genres("Action|Sci-Fi|IMAX") == (28, 878)
    assert map_genres("Children|Musical") == (10751, 10402)
    assert map_genres("Crime|Film-Noir") == (80,)  # deduped
    assert map_genres("(no genres listed)") == ()
    assert set(MOVIELENS_TO_TMDB_GENRE.values()) <= set(GENRE_IDS)


def test_parse_title_year() -> None:
    assert parse_title_year("Toy Story (1995)") == ("Toy Story", 1995)
    assert parse_title_year("Babylon 5") == ("Babylon 5", None)


def test_parse_links_skips_missing_tmdb() -> None:
    assert parse_links(io.StringIO(LINKS)) == {1: 862, 2: 8844, 4: 862, 5: 11862}


def test_parse_ratings_latest_wins_for_duplicate_tmdb() -> None:
    events = parse_ratings(io.StringIO(RATINGS), parse_links(io.StringIO(LINKS)))
    user1 = {e.movie_id: e for e in events if e.user_id == 1}
    # movieId 1 and 4 both map to TMDB 862; the later rating (2.5 -> 5) wins
    assert user1[862].score == 5
    assert user1[8844].score == 1
    assert 3 not in {e.movie_id for e in events}  # no TMDB id -> dropped
    assert len(events) == 3


def test_build_dataset() -> None:
    ds = build_dataset(io.StringIO(LINKS), io.StringIO(MOVIES), io.StringIO(RATINGS))
    assert sorted(ds.movies) == [862, 8844, 11862]
    toy = ds.movies[862]
    assert toy.movielens_id == 1  # canonical (first) MovieLens id
    assert toy.title == "Toy Story" and toy.year == 1995
    assert toy.genre_ids == (12, 16, 10751, 35, 14)
    assert ds.movies[11862].genre_ids == ()
    assert ds.user_ids == [1, 2]


def test_load_movielens_from_dir(tiny_data_dir: Path) -> None:
    ds = load_movielens(tiny_data_dir)
    assert len(ds.movies) == 40
    assert all(1 <= r.score <= 10 for r in ds.ratings)


def test_load_movielens_missing_files(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match="download_movielens"):
        load_movielens(tmp_path)
