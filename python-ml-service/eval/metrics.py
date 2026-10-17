"""Pure metric functions. `recs` is a ranked list of movie ids (best first), `relevant` a set.

Relevance is binary: a test item is relevant when its 1-10 test rating >= the threshold
(default 8). NDCG uses gain 1 / log2(rank + 1) with rank starting at 1.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence


def _hits(recs: Sequence[int], relevant: set[int], k: int) -> int:
    return sum(1 for m in recs[:k] if m in relevant)


def precision_at_k(recs: Sequence[int], relevant: set[int], k: int) -> float:
    """Hits in the top k divided by k (a short list is penalised, not normalised)."""
    return _hits(recs, relevant, k) / k if k > 0 else 0.0


def recall_at_k(recs: Sequence[int], relevant: set[int], k: int) -> float:
    return _hits(recs, relevant, k) / len(relevant) if relevant else 0.0


def hit_rate_at_k(recs: Sequence[int], relevant: set[int], k: int) -> float:
    return 1.0 if _hits(recs, relevant, k) > 0 else 0.0


def ndcg_at_k(recs: Sequence[int], relevant: set[int], k: int) -> float:
    if not relevant or k <= 0:
        return 0.0
    dcg = sum(1.0 / math.log2(i + 2) for i, m in enumerate(recs[:k]) if m in relevant)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(min(len(relevant), k)))
    return dcg / idcg


def catalog_coverage(rec_lists: Iterable[Sequence[int]], catalog_size: int, k: int) -> float:
    """Distinct items appearing in any top-k list / catalog size."""
    if catalog_size <= 0:
        return 0.0
    distinct = {m for recs in rec_lists for m in recs[:k]}
    return len(distinct) / catalog_size


def mean_popularity_rank(
    rec_lists: Iterable[Sequence[int]], popularity_rank: dict[int, int], k: int
) -> float:
    """Mean popularity rank (1 = most-rated movie in train) over all recommended items.

    Lower = more popularity-biased. Items missing from `popularity_rank` are skipped.
    """
    ranks = [popularity_rank[m] for recs in rec_lists for m in recs[:k] if m in popularity_rank]
    return sum(ranks) / len(ranks) if ranks else float("nan")


def mean(values: Sequence[float]) -> float:
    return sum(values) / len(values) if values else float("nan")


def std(values: Sequence[float]) -> float:
    """Population standard deviation."""
    if not values:
        return float("nan")
    mu = mean(values)
    return math.sqrt(sum((v - mu) ** 2 for v in values) / len(values))


# ---- group ------------------------------------------------------------------


def member_satisfactions(
    recs: Sequence[int], member_relevant: Sequence[set[int]], k: int
) -> list[float]:
    """Per-member NDCG@k of the shared group list against each member's own test items."""
    return [ndcg_at_k(recs, rel, k) for rel in member_relevant]


def group_summary(recs: Sequence[int], member_relevant: Sequence[set[int]], k: int) -> dict:
    """Metrics for ONE group: mean / min / std of member satisfaction, all-members-hit."""
    sats = member_satisfactions(recs, member_relevant, k)
    return {
        "mean_satisfaction": mean(sats),
        "min_satisfaction": min(sats) if sats else float("nan"),
        "satisfaction_std": std(sats),
        "all_members_hit": all(hit_rate_at_k(recs, rel, k) > 0 for rel in member_relevant),
    }
