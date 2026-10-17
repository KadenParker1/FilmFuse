from eval.data import Dataset, RatingEvent
from eval.split import eligible_users, relevant_items, sample_users, temporal_split


def _ev(user: int, movie: int, ts: int, score: int = 8) -> RatingEvent:
    return RatingEvent(user_id=user, movie_id=movie, score=score, timestamp=ts)


def test_last_20_percent_by_time_is_test() -> None:
    ratings = [_ev(1, m, ts=100 - m) for m in range(1, 11)]  # movie 10 is oldest
    split = temporal_split(ratings, test_fraction=0.2, min_ratings=5)
    assert [r.movie_id for r in split.test[1]] == [2, 1]
    assert len(split.train[1]) == 8
    assert max(r.timestamp for r in split.train[1]) <= min(r.timestamp for r in split.test[1])


def test_timestamp_ties_broken_by_movie_id() -> None:
    ratings = [_ev(1, m, ts=5) for m in (9, 3, 7, 1, 5)]
    split = temporal_split(ratings, test_fraction=0.2, min_ratings=5)
    assert [r.movie_id for r in split.test[1]] == [9]


def test_min_history_threshold() -> None:
    ratings = [_ev(1, m, ts=m) for m in range(1, 6)] + [_ev(2, m, ts=m) for m in range(1, 4)]
    split = temporal_split(ratings, min_ratings=5)
    assert split.users == [1]


def test_split_is_deterministic(tiny_dataset: Dataset) -> None:
    a = temporal_split(tiny_dataset.ratings)
    b = temporal_split(list(reversed(tiny_dataset.ratings)))
    assert a.train == b.train and a.test == b.test


def test_relevance_and_eligibility() -> None:
    ratings = [_ev(1, m, ts=m, score=10 if m == 5 else 4) for m in range(1, 6)]
    ratings += [_ev(2, m, ts=m, score=4) for m in range(1, 6)]
    split = temporal_split(ratings, test_fraction=0.2, min_ratings=5)
    assert relevant_items(split.test[1], 8) == {5}
    assert eligible_users(split, 8) == [1]


def test_sample_users_seeded() -> None:
    users = list(range(100))
    assert sample_users(users, 10, seed=1) == sample_users(users, 10, seed=1)
    assert sample_users(users, 10, seed=1) != sample_users(users, 10, seed=2)
    assert sample_users(users, 500, seed=1) == users
