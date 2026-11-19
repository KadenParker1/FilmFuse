import numpy as np
import pytest

from app.services.collaborative import EASE, ItemKNN, PostgresRatingsSource, StaticSource

# Items 1 and 2 are loved/hated together; item 3 goes the opposite way; item 4 is noise.
# Item 9 has only one rating (below MIN_ITEM_RATINGS) and must be ignored.
TRIPLES = [
    ("a", 1, 10), ("a", 2, 9), ("a", 3, 2), ("a", 4, 6),
    ("b", 1, 9), ("b", 2, 10), ("b", 3, 3), ("b", 4, 5),
    ("c", 1, 2), ("c", 2, 3), ("c", 3, 9), ("c", 4, 6),
    ("d", 1, 3), ("d", 2, 2), ("d", 3, 10), ("d", 4, 5), ("d", 9, 10),
    ("e", 1, 8), ("e", 2, 8), ("e", 3, 4), ("e", 4, 7),
]  # fmt: skip


def neighbours(model: ItemKNN, item: int) -> dict[int, float]:
    row = model.index[item]
    return {
        model.items[j]: float(s) for j, s in zip(model.nb_idx[row], model.nb_sim[row], strict=True)
    }


def test_knn_learns_co_liked_and_opposite_items() -> None:
    m = ItemKNN(TRIPLES, k=3, shrink=0.0)
    assert 9 not in m.index  # too few ratings
    n1 = neighbours(m, 1)
    assert n1[2] > 0.8
    assert n1[3] < -0.8


def test_shrinkage_lowers_similarity() -> None:
    raw = neighbours(ItemKNN(TRIPLES, k=3, shrink=0.0), 1)[2]
    shrunk = neighbours(ItemKNN(TRIPLES, k=3, shrink=10.0), 1)[2]
    assert 0 < shrunk < raw


def test_top_k_cap() -> None:
    m = ItemKNN(TRIPLES, k=2)
    assert m.nb_idx.shape == (4, 2)


def test_prediction_follows_neighbours_and_names_the_reason() -> None:
    m = ItemKNN(TRIPLES, k=3, shrink=0.0)
    # A new user who loved 1 and hated 3: should like 2.
    pred, support, top = m.predict({1: 10, 3: 2}, [2, 4, 404])
    mean = 6.0
    assert pred[0] > mean + 2
    assert support[0] > 0 and top[0] == 1
    # Unknown movie: no evidence, prediction is the user's mean.
    assert support[2] == 0 and pred[2] == pytest.approx(mean) and top[2] is None


def test_opposite_taste_predicts_low() -> None:
    m = ItemKNN(TRIPLES, k=3, shrink=0.0)
    pred, _, _ = m.predict({1: 9, 3: 1}, [3])
    assert pred[0] < 5


def test_no_ratings_no_evidence() -> None:
    m = ItemKNN(TRIPLES)
    pred, support, top = m.predict({}, [1, 2])
    assert support.tolist() == [0.0, 0.0] and top == [None, None]


def test_degenerate_inputs() -> None:
    assert ItemKNN([]).predict({1: 5}, [1])[1].tolist() == [0.0]
    single = ItemKNN([("a", 1, 5), ("b", 1, 6), ("c", 1, 7)])
    assert single.predict({1: 5}, [1])[1].tolist() == [0.0]
    pred, *_ = ItemKNN(TRIPLES).predict({1: 10}, [2])
    assert 1.0 <= pred[0] <= 10.0


def test_ease_recovers_the_obvious_pair() -> None:
    m = EASE(TRIPLES, lam=1.0)
    pred, support, top = m.predict({1: 10, 3: 2}, [2])
    assert pred[0] > 7 and support[0] > 0 and top[0] in (1, 3)


def test_static_source() -> None:
    assert StaticSource(None).model() is None


class FakeSource(PostgresRatingsSource):
    def __init__(self, rows, fail=False):
        super().__init__("postgresql://fake", refresh_seconds=3600.0, background=False)
        self.rows, self.fail, self.calls = rows, fail, 0

    def _query(self):
        self.calls += 1
        if self.fail:
            raise RuntimeError("db down")
        return self.rows


def test_postgres_source_builds_once_until_stale() -> None:
    src = FakeSource(TRIPLES)
    model = src.model()
    assert isinstance(model, ItemKNN) and 1 in model.index
    assert src.model() is model and src.calls == 1


def test_postgres_source_warm_in_background() -> None:
    src = FakeSource(TRIPLES)
    src.warm()
    src._build_lock.acquire()  # waits for the warm-up build to finish
    src._build_lock.release()
    assert src.model() is not None and src.calls == 1


def test_postgres_source_failure_means_content_only() -> None:
    assert FakeSource(TRIPLES, fail=True).model() is None


def test_knn_handles_many_items_in_blocks() -> None:
    rng = np.random.default_rng(0)
    triples = [(u, i, int(rng.integers(1, 11))) for u in range(30) for i in range(1300)]
    m = ItemKNN(triples, k=5)
    assert m.nb_idx.shape == (1300, 5)
    assert not (m.nb_idx == np.arange(1300)[:, None]).any()  # never its own neighbour
