import numpy as np

from app.services.features import (
    ANIMATION,
    COMEDY,
    FAMILY,
    GENRE_INDEX,
    HORROR,
    KEYWORD_FLOOR,
    MOOD_INDEX,
    MOOD_RULES,
    SCIFI,
    dim_name,
    feature_vector,
    is_genre_dim,
    mood_strengths,
)
from app.services.recommender import GENRE_IDS, INTERPRETABLE_DIM, MOOD_TAGS


def test_vector_layout_matches_interpretable_order() -> None:
    v = feature_vector((HORROR, COMEDY), frozenset())
    assert v.shape == (INTERPRETABLE_DIM,)
    assert v[GENRE_INDEX[HORROR]] == 1.0 and v[GENRE_INDEX[COMEDY]] == 1.0
    assert v[: len(GENRE_IDS)].sum() == 2.0


def test_unknown_genre_ignored() -> None:
    v = feature_vector((424242,), frozenset())
    assert v[: len(GENRE_IDS)].sum() == 0.0


def test_every_mood_has_a_rule() -> None:
    assert set(MOOD_RULES) == set(MOOD_TAGS)


def test_genre_hints_set_moods() -> None:
    m = mood_strengths((COMEDY,), frozenset())
    assert m[MOOD_TAGS.index("laugh-out-loud")] == MOOD_RULES["laugh-out-loud"].genres[COMEDY]
    assert m[MOOD_TAGS.index("dark-gritty")] == 0.0


def test_keyword_confirms_and_lifts_a_mood() -> None:
    plain = mood_strengths((SCIFI,), frozenset())
    twisty = mood_strengths((SCIFI,), frozenset({"time travel"}))
    i = MOOD_TAGS.index("mind-bending")
    assert twisty[i] > plain[i]
    # A keyword alone (no hinting genre) still reaches the floor.
    only_kw = mood_strengths((), frozenset({"christmas"}))
    assert only_kw[MOOD_TAGS.index("cozy")] == KEYWORD_FLOOR


def test_strengths_stay_within_0_1() -> None:
    m = mood_strengths((ANIMATION, FAMILY), frozenset({"christmas", "friendship", "magic"}))
    assert np.all((m >= 0) & (m <= 1))


def test_dim_names() -> None:
    assert dim_name(GENRE_INDEX[HORROR]) == "Horror"
    assert dim_name(MOOD_INDEX["cozy"]) == "cozy films"
    assert is_genre_dim(0) and not is_genre_dim(MOOD_INDEX["cozy"])
