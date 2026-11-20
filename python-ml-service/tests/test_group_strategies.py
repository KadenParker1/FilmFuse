import numpy as np
import pytest

from app.models.contract import Strategy
from app.services.group_strategies import aggregate, group_closeness, rank_relevance

# 2 members x 4 movies.
#           m0   m1   m2   m3
SCORES = np.array(
    [
        [9.0, 6.0, 2.0, 7.0],  # member 0
        [2.0, 6.0, 10.0, 7.5],  # member 1
    ]
)
ONES = np.ones(2)


def order(strategy: Strategy, scores=SCORES, weights=ONES, closeness=(), k=4) -> list[int]:
    return [i for i, _ in aggregate(strategy, scores, weights, list(closeness), k)]


def test_mean() -> None:
    assert order(Strategy.MEAN)[0] == 3  # 7.25
    ranked = aggregate(Strategy.MEAN, SCORES, ONES, [], 4)
    assert ranked[0][1] == pytest.approx(7.25)


def test_least_misery_maximises_the_minimum() -> None:
    assert order(Strategy.LEAST_MISERY)[:2] == [3, 1]
    ranked = aggregate(Strategy.LEAST_MISERY, SCORES, ONES, [], 1)
    assert ranked[0][1] == 7.0


def test_most_pleasure_maximises_the_maximum() -> None:
    assert order(Strategy.MOST_PLEASURE)[0] == 2


def test_weights_change_the_mean() -> None:
    assert order(Strategy.MEAN, weights=np.array([1.0, 0.0]))[0] == 0
    assert order(Strategy.MEAN, weights=np.array([0.0, 1.0]))[0] == 2


def test_zero_weight_members_dont_count_for_misery() -> None:
    assert order(Strategy.LEAST_MISERY, weights=np.array([1.0, 0.0]))[0] == 0


def test_closeness_interpolates_mean_and_misery() -> None:
    s = np.array([[10.0, 6.5], [3.0, 6.0]])  # m0: mean 6.5 min 3; m1: mean 6.25 min 6
    tight = [[1.0, 1.0], [1.0, 1.0]]
    loose = [[1.0, 0.0], [0.0, 1.0]]
    assert order(Strategy.CLOSENESS_WEIGHTED, s, closeness=tight, k=2)[0] == 0  # like mean
    assert order(Strategy.CLOSENESS_WEIGHTED, s, closeness=loose, k=2)[0] == 1  # like misery


def test_group_closeness() -> None:
    assert group_closeness([], 3) == 0.5
    assert group_closeness([[1.0]], 1) == 0.5
    assert group_closeness([[1, 0.2, 0.4], [0.2, 1, 0.6], [0.4, 0.6, 1]], 3) == pytest.approx(0.4)
    assert group_closeness([[1, 5], [5, 1]], 2) == 1.0  # clamped


def test_fairness_alternates_between_members() -> None:
    # Member 0 loves m0/m1, member 1 loves m2/m3; nobody hates anything.
    s = np.array([[10.0, 9.5, 5.0, 5.0], [5.0, 5.0, 10.0, 9.5]])
    first_two = order(Strategy.FAIRNESS, s, k=2)
    assert {first_two[0] in (0, 1), first_two[1] in (2, 3)} == {True}


def test_fairness_avoids_misery_until_it_must() -> None:
    s = np.array([[10.0, 6.0], [1.0, 6.0]])
    assert order(Strategy.FAIRNESS, s, k=2) == [1, 0]


def test_k_and_empty() -> None:
    assert len(order(Strategy.MEAN, k=2)) == 2
    assert aggregate(Strategy.MEAN, np.zeros((2, 0)), ONES, [], 5) == []
    assert len(order(Strategy.FAIRNESS, k=10)) == 4


def test_rank_relevance() -> None:
    r = rank_relevance(np.array([[1.0, 9.0, 5.0, 7.0]]), depth=2)
    assert r.tolist() == [[0.0, 1.0, 0.0, 0.5]]


def test_fairness_isnt_fooled_by_a_generous_scorer() -> None:
    # Member 0 scores everything high, member 1 is picky. Each still gets their own favourite
    # in the first two picks.
    s = np.array([[15.0, 14.0, 13.0, 12.5], [5.0, 5.5, 6.0, 9.0]])
    first_two = order(Strategy.FAIRNESS, s, k=2)
    assert set(first_two) == {0, 3}


def test_soft_misery_for_three_or_more() -> None:
    # m0: one member hates it (1), others love it; m1: everyone lukewarm (5).
    s = np.array([[1.0, 5.0], [10.0, 5.0], [10.0, 5.0], [10.0, 5.0]])
    # Hard minimum would pick m1 (5 > 1); the least happy half of m0 averages (1+10)/2 = 5.5.
    assert order(Strategy.LEAST_MISERY, s, weights=np.ones(4), k=2)[0] == 0
    # Pairs keep the hard minimum.
    pair = np.array([[1.0, 5.0], [10.0, 5.0]])
    assert order(Strategy.LEAST_MISERY, pair, weights=np.ones(2), k=2)[0] == 1
