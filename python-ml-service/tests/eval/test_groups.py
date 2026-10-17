import numpy as np
import pytest

from eval.data import Dataset, RatingEvent
from eval.groups import cosine_matrix, make_groups, mean_pairwise_similarity
from eval.split import temporal_split


def test_cosine_matrix_hand_computed() -> None:
    train = {
        1: [RatingEvent(1, 10, 2, 0), RatingEvent(1, 20, 2, 0)],
        2: [RatingEvent(2, 10, 5, 0)],
        3: [RatingEvent(3, 30, 7, 0)],
    }
    sim = cosine_matrix([1, 2, 3], train)
    assert sim[0, 1] == pytest.approx(1 / np.sqrt(2))
    assert sim[0, 2] == pytest.approx(0.0)
    assert np.allclose(np.diag(sim), 1.0)


# users 0,1,2 are near-identical; 3,4,5 are orthogonal to each other and to 0-2
SIM = np.array(
    [
        [1.0, 0.9, 0.9, 0.0, 0.0, 0.0],
        [0.9, 1.0, 0.9, 0.0, 0.0, 0.0],
        [0.9, 0.9, 1.0, 0.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 1.0, 0.0],
        [0.0, 0.0, 0.0, 0.0, 0.0, 1.0],
    ]
)
USERS = [10, 11, 12, 13, 14, 15]


@pytest.mark.parametrize("method", ["random", "similar", "dissimilar"])
def test_groups_deterministic_and_sized(method: str) -> None:
    a = make_groups(USERS, SIM, 20, method, seed=3, min_size=2, max_size=4)
    assert a == make_groups(USERS, SIM, 20, method, seed=3, min_size=2, max_size=4)
    assert len(a) == 20
    for g in a:
        assert 2 <= len(g.members) <= 4
        assert len(set(g.members)) == len(g.members)
        assert set(g.members) <= set(USERS)


def test_similar_groups_are_more_similar_than_dissimilar() -> None:
    sim_groups = make_groups(USERS, SIM, 30, "similar", seed=1, min_size=3, max_size=3)
    dis_groups = make_groups(USERS, SIM, 30, "dissimilar", seed=1, min_size=3, max_size=3)
    for g in sim_groups:
        if g.members[0] in (10, 11, 12):
            assert set(g.members) == {10, 11, 12}
    for g in dis_groups:
        assert mean_pairwise_similarity(g, USERS, SIM) == pytest.approx(0.0)


def test_unknown_method() -> None:
    with pytest.raises(ValueError):
        make_groups(USERS, SIM, 1, "cliques", seed=0)


def test_groups_on_tiny_dataset(tiny_dataset: Dataset) -> None:
    split = temporal_split(tiny_dataset.ratings)
    sim = cosine_matrix(split.users, split.train)
    assert sim.shape == (len(split.users), len(split.users))
    assert make_groups(split.users, sim, 5, "similar", seed=42)
