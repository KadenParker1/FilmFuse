import numpy as np
import pytest

from app.services.catalog import UserTaste
from app.services.group_profile import merge_tastes, merge_vectors, rating_cosine


def test_merge_centres_each_member_then_shifts_back() -> None:
    generous = UserTaste(ratings={1: 10, 2: 8}, loved=[1], n_signals=2)  # mean 9
    harsh = UserTaste(ratings={1: 5, 3: 1}, loved=[], disliked={3}, n_signals=2)  # mean 3
    merged = merge_tastes([generous, harsh])
    base = 6.0  # average of the members' means
    assert merged.ratings[1] == pytest.approx(base + (1 + 2) / 2)  # both rated it above their mean
    assert merged.ratings[2] == pytest.approx(base - 1)
    assert merged.ratings[3] == pytest.approx(base - 2)
    assert merged.n_signals == 4


def test_merge_interleaves_loved_and_unions_dislikes() -> None:
    a = UserTaste(loved=[1, 2, 3], disliked={9})
    b = UserTaste(loved=[7, 1], disliked={2, 8}, dismissed={5})
    merged = merge_tastes([a, b])
    assert merged.loved == [1, 7, 2, 3]
    assert merged.disliked == {8, 9}  # 2 is loved by someone, so not a group dislike
    assert merged.dismissed == {5}


def test_merge_with_nobody_rated() -> None:
    merged = merge_tastes([UserTaste(), UserTaste()])
    assert merged.ratings == {} and merged.loved == []


def test_merge_vectors() -> None:
    v = merge_vectors([np.array([2.0, 8.0]), None, np.array([4.0, 6.0])])
    assert v is not None and v.interpretable == [3.0, 7.0]
    assert merge_vectors([None]) is None


def test_rating_cosine() -> None:
    assert rating_cosine([{1: 9, 2: 3}, {1: 9, 2: 3}]) == pytest.approx(1.0)
    assert rating_cosine([{1: 9}, {2: 9}]) == 0.0
    assert rating_cosine([{1: 9}, {}]) == 0.0
    assert rating_cosine([{1: 9}]) == 0.0
