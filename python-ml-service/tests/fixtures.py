"""A small, hand-made movie catalog for content-v1 tests (TMDB ids, genres, a few keywords).

Votes and popularity are rough, only their order matters. No database needed.
"""

from __future__ import annotations

from functools import lru_cache

from app.services.catalog import InMemoryCatalog, InMemoryTasteStore, MovieFeatures
from app.services.content_recommender import ContentRecommender
from app.services.features import (
    ACTION,
    ADVENTURE,
    ANIMATION,
    COMEDY,
    CRIME,
    DOCUMENTARY,
    DRAMA,
    FAMILY,
    FANTASY,
    HORROR,
    MUSIC,
    MYSTERY,
    ROMANCE,
    SCIFI,
    THRILLER,
)

# id, title, year, genres, keywords, popularity, vote_average, vote_count
# ruff: noqa: E501 (one movie per line reads better)
_ROWS = [
    (278, "The Shawshank Redemption", 1994, (DRAMA, CRIME), {"prison", "friendship"}, 90, 8.7, 27000),
    (238, "The Godfather", 1972, (DRAMA, CRIME), {"gangster", "mafia"}, 80, 8.7, 20000),
    (155, "The Dark Knight", 2008, (DRAMA, ACTION, CRIME, THRILLER), {"superhero", "joker"}, 120, 8.5, 32000),
    (680, "Pulp Fiction", 1994, (THRILLER, CRIME), {"gangster", "nonlinear timeline"}, 70, 8.5, 27000),
    (550, "Fight Club", 1999, (DRAMA,), {"twist ending", "dual identity"}, 75, 8.4, 29000),
    (13, "Forrest Gump", 1994, (COMEDY, DRAMA, ROMANCE), {"vietnam war", "friendship"}, 85, 8.5, 26000),
    (603, "The Matrix", 1999, (ACTION, SCIFI), {"artificial intelligence", "dystopia"}, 95, 8.2, 25000),
    (27205, "Inception", 2010, (ACTION, SCIFI, ADVENTURE), {"dream", "heist"}, 110, 8.4, 36000),
    (129, "Spirited Away", 2001, (ANIMATION, FAMILY, FANTASY), {"magic", "spirit"}, 60, 8.5, 16000),
    (120, "The Fellowship of the Ring", 2001, (ADVENTURE, FANTASY, ACTION), {"epic", "magic"}, 100, 8.4, 25000),
    (12, "Finding Nemo", 2003, (ANIMATION, FAMILY), {"fish", "parent child relationship"}, 70, 7.8, 19000),
    (862, "Toy Story", 1995, (ANIMATION, ADVENTURE, FAMILY, COMEDY), {"friendship", "toy"}, 90, 8.0, 18000),
    (277834, "Moana", 2016, (ADVENTURE, COMEDY, FAMILY, ANIMATION), {"ocean", "musical"}, 80, 7.6, 12000),
    (109445, "Frozen", 2013, (ANIMATION, FAMILY, ADVENTURE, FANTASY), {"musical", "sister"}, 85, 7.2, 16000),
    (354912, "Coco", 2017, (FAMILY, ANIMATION, MUSIC, ADVENTURE), {"musical", "death"}, 75, 8.2, 19000),
    (694, "The Shining", 1980, (HORROR, THRILLER), {"hotel", "isolation"}, 50, 8.2, 17000),
    (138843, "The Conjuring", 2013, (HORROR, THRILLER), {"supernatural", "ghost"}, 60, 7.5, 11000),
    (493922, "Hereditary", 2018, (HORROR, MYSTERY, THRILLER), {"supernatural", "grief"}, 40, 7.3, 7000),
    (419430, "Get Out", 2017, (MYSTERY, THRILLER, HORROR), {"hypnosis", "twist ending"}, 45, 7.6, 16000),
    (346364, "It", 2017, (HORROR, FANTASY), {"clown", "supernatural"}, 55, 7.2, 19000),
    (8363, "Superbad", 2007, (COMEDY,), {"high school", "buddy comedy"}, 30, 7.2, 7500),
    (18785, "The Hangover", 2009, (COMEDY,), {"las vegas", "buddy comedy"}, 40, 7.3, 16000),
    (11036, "The Notebook", 2004, (ROMANCE, DRAMA), {"love", "dementia"}, 45, 7.9, 11000),
    (76492, "Hotel Transylvania", 2012, (ANIMATION, COMEDY, FAMILY, FANTASY), {"monster", "vampire"}, 50, 7.0, 8000),
    (14836, "Coraline", 2009, (ANIMATION, FAMILY, FANTASY), {"other world", "witch"}, 40, 7.4, 7000),
    (437586, "Free Solo", 2018, (DOCUMENTARY,), {"climbing", "nature"}, 15, 7.9, 1500),
    (99999, "Obscure Drama", 2015, (DRAMA,), set(), 1, 5.0, 5),
]  # fmt: skip

HORROR_IDS = {694, 138843, 493922, 419430, 346364}
FAMILY_IDS = {129, 12, 862, 277834, 109445, 354912, 76492, 14836}
ALL_IDS = [r[0] for r in _ROWS]


def fixture_movies() -> list[MovieFeatures]:
    return [
        MovieFeatures(i, t, y, g, frozenset(k), float(p), va, vc)
        for i, t, y, g, k, p, va, vc in _ROWS
    ]


@lru_cache
def _obscure_tail() -> tuple[MovieFeatures, ...]:
    """6000 little-known films (ids 1_000_000+), like the long tail of the real catalog, so
    the named films above sit in the popular head as they do in production."""
    genres = (DRAMA, COMEDY, HORROR, DOCUMENTARY, ROMANCE, THRILLER)
    return tuple(
        MovieFeatures(
            1_000_000 + i, f"Obscure {i}", 2000, (genres[i % 6],), frozenset(), 0.1, 6.0, 3
        )
        for i in range(6000)
    )


def fixture_catalog() -> InMemoryCatalog:
    return InMemoryCatalog(fixture_movies() + list(_obscure_tail()))


def fixture_recommender(tastes: InMemoryTasteStore | None = None) -> ContentRecommender:
    return ContentRecommender(fixture_catalog(), tastes or InMemoryTasteStore())
