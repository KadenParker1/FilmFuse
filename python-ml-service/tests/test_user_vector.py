from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from app.models.contract import (
    Favorite,
    GenrePref,
    MoodPref,
    Rating,
    UserVector,
    UserVectorRequest,
)
from app.services.features import ANIMATION, COMEDY, FAMILY, GENRE_INDEX, HORROR, MOOD_INDEX
from app.services.recommender import INTERPRETABLE_DIM, PlaceholderRecommender
from tests.fixtures import fixture_recommender

USER = UUID("11111111-1111-1111-1111-111111111111")
NOW = datetime(2026, 11, 1, tzinfo=UTC)


def _rating(movie_id: int, score: int, days_ago: int = 0) -> Rating:
    return Rating(
        movie_id=movie_id, score=score, source="quick_rate", created_at=NOW - timedelta(days_ago)
    )


def _vector(**kwargs) -> list[float]:
    resp = fixture_recommender().build_user_vector(UserVectorRequest(user_id=USER, **kwargs))
    assert resp.dim == INTERPRETABLE_DIM == len(resp.vector.interpretable)
    assert resp.model_version == "hybrid-v6"
    assert resp.vector.latent is None
    return resp.vector.interpretable


def test_stated_prefs_alone_match_the_old_placeholder() -> None:
    req = UserVectorRequest(
        user_id=USER,
        genre_prefs=[GenrePref(genre_id=HORROR, score=9), GenrePref(genre_id=COMEDY, score=2)],
        mood_prefs=[MoodPref(mood_tag="cozy", score=8)],
    )
    ours = fixture_recommender().build_user_vector(req).vector.interpretable
    old = PlaceholderRecommender().build_user_vector(req).vector.interpretable
    assert ours == pytest.approx(old)


def test_high_ratings_raise_their_genres() -> None:
    base = _vector()
    rated = _vector(ratings=[_rating(694, 10), _rating(138843, 9), _rating(346364, 9)])
    assert rated[GENRE_INDEX[HORROR]] > base[GENRE_INDEX[HORROR]] + 2
    assert rated[GENRE_INDEX[ANIMATION]] == base[GENRE_INDEX[ANIMATION]]  # untouched


def test_low_ratings_lower_their_genres() -> None:
    v = _vector(ratings=[_rating(694, 1), _rating(138843, 2)])
    assert v[GENRE_INDEX[HORROR]] < 3


def test_favourites_push_up_and_least_favourites_push_down() -> None:
    fav = _vector(favorites=[Favorite(movie_id=12, rank=1)])  # Finding Nemo
    least = _vector(least_favorites=[Favorite(movie_id=12, rank=1)])
    assert fav[GENRE_INDEX[FAMILY]] > 6.5
    assert least[GENRE_INDEX[FAMILY]] < 3.5


def test_favourite_outweighs_a_single_rating() -> None:
    fav = _vector(favorites=[Favorite(movie_id=694, rank=1)])
    rated = _vector(ratings=[_rating(694, 10)])
    assert fav[GENRE_INDEX[HORROR]] > rated[GENRE_INDEX[HORROR]]


def test_dismissals_are_mild() -> None:
    v = _vector(dismissals=[694])
    assert 3.5 < v[GENRE_INDEX[HORROR]] < 5.0


def test_stated_prefs_resist_one_contrary_rating() -> None:
    v = _vector(genre_prefs=[GenrePref(genre_id=HORROR, score=9)], ratings=[_rating(694, 3)])
    assert v[GENRE_INDEX[HORROR]] > 6.5


def test_recent_ratings_count_more() -> None:
    recent_love = _vector(ratings=[_rating(694, 10, 0), _rating(138843, 2, 1500)])
    recent_hate = _vector(ratings=[_rating(694, 10, 1500), _rating(138843, 2, 0)])
    assert recent_love[GENRE_INDEX[HORROR]] > recent_hate[GENRE_INDEX[HORROR]]


def test_moods_learn_from_keywords() -> None:
    # Coco carries "musical": loving it lifts feel-good.
    v = _vector(ratings=[_rating(354912, 10)])
    assert v[MOOD_INDEX["feel-good"]] > 5.0


def test_unknown_movies_are_skipped_and_values_clamped() -> None:
    v = _vector(ratings=[_rating(123456789, 10)], previous_vector=UserVector(interpretable=[99.0]))
    assert v == pytest.approx([5.0] * INTERPRETABLE_DIM)
    assert all(1.0 <= x <= 10.0 for x in _vector(ratings=[_rating(694, 10)] * 50))
