import math

import pytest

from eval import metrics as m

RECS = [1, 2, 3, 4]
REL = {2, 4, 9}
L2, L3, L4, L5 = (math.log2(x) for x in (2, 3, 4, 5))


def test_precision() -> None:
    assert m.precision_at_k(RECS, REL, 4) == 0.5
    assert m.precision_at_k(RECS, REL, 2) == 0.5
    assert m.precision_at_k(RECS, REL, 1) == 0.0
    assert m.precision_at_k([2], REL, 4) == 0.25  # short lists are penalised


def test_recall() -> None:
    assert m.recall_at_k(RECS, REL, 4) == pytest.approx(2 / 3)
    assert m.recall_at_k(RECS, REL, 2) == pytest.approx(1 / 3)
    assert m.recall_at_k(RECS, set(), 4) == 0.0


def test_hit_rate() -> None:
    assert m.hit_rate_at_k(RECS, REL, 2) == 1.0
    assert m.hit_rate_at_k(RECS, REL, 1) == 0.0


def test_ndcg_hand_computed() -> None:
    # hits at ranks 2 and 4; ideal = 3 hits at ranks 1..3
    expected = (1 / L3 + 1 / L5) / (1 / L2 + 1 / L3 + 1 / L4)
    assert m.ndcg_at_k(RECS, REL, 4) == pytest.approx(expected)
    assert m.ndcg_at_k(RECS, REL, 4) == pytest.approx(0.49819, abs=1e-5)
    # k=2: one hit at rank 2; ideal = 2 hits
    assert m.ndcg_at_k(RECS, REL, 2) == pytest.approx((1 / L3) / (1 + 1 / L3))
    assert m.ndcg_at_k([2, 4, 9], REL, 3) == pytest.approx(1.0)
    assert m.ndcg_at_k(RECS, set(), 4) == 0.0


def test_catalog_coverage() -> None:
    assert m.catalog_coverage([[1, 2], [2, 3]], 10, 2) == pytest.approx(0.3)
    assert m.catalog_coverage([[1, 2], [2, 3]], 10, 1) == pytest.approx(0.2)
    assert m.catalog_coverage([], 0, 1) == 0.0


def test_mean_popularity_rank() -> None:
    ranks = {1: 1, 2: 2, 3: 5}
    assert m.mean_popularity_rank([[1, 2], [3, 99]], ranks, 2) == pytest.approx(8 / 3)
    assert math.isnan(m.mean_popularity_rank([], ranks, 2))


def test_mean_std() -> None:
    assert m.mean([1.0, 2.0, 3.0]) == 2.0
    assert m.std([1.0, 3.0]) == 1.0
    assert math.isnan(m.mean([]))


def test_group_summary_hand_computed() -> None:
    members = [{1}, {2}, {9}]
    sats = m.member_satisfactions([1, 2], members, 2)
    assert sats == pytest.approx([1.0, 1 / L3, 0.0])
    s = m.group_summary([1, 2], members, 2)
    mu = (1 + 1 / L3) / 3
    assert s["mean_satisfaction"] == pytest.approx(mu)
    assert s["min_satisfaction"] == 0.0
    expected_std = math.sqrt(((1 - mu) ** 2 + (1 / L3 - mu) ** 2 + mu**2) / 3)
    assert s["satisfaction_std"] == pytest.approx(expected_std)
    assert s["all_members_hit"] is False
    assert m.group_summary([1, 2], [{1}, {2}], 2)["all_members_hit"] is True
