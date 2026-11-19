from uuid import UUID

import pytest

from app.services import catalog as cat
from app.services.catalog import (
    EMPTY_TASTE,
    InMemoryCatalog,
    MovieFeatures,
    PostgresCatalog,
    PostgresTasteStore,
    taste_from_rows,
)

USER = UUID("11111111-1111-1111-1111-111111111111")

ROWS = [
    (1, "Big Hit", 2010, [28, 12], ["heist"], 500.0, 8.0, 20000),
    (2, "Small Film", 2015, [18], [], 0.5, 6.0, 4),
    (3, "No Votes", None, [], None, None, None, None),
]


class FakeCatalog(PostgresCatalog):
    def __init__(self, rows, extra_rows=(), fail=False, refresh_seconds=3600.0):
        super().__init__(
            "postgresql://fake", refresh_seconds=refresh_seconds, background_refresh=False
        )
        self.rows, self.extra_rows, self.fail = list(rows), list(extra_rows), fail
        self.queries: list[tuple[str, tuple]] = []

    def _query(self, sql, params=()):
        self.queries.append((sql, params))
        if self.fail:
            raise RuntimeError("db down")
        if "any(%s)" in sql:
            wanted = set(params[0])
            return [r for r in self.extra_rows if r[0] in wanted]
        return self.rows


def test_postgres_catalog_loads_once_and_maps_rows() -> None:
    c = FakeCatalog(ROWS)
    got = c.get_many([1, 2, 3])
    assert set(got) == {1, 2, 3}
    assert got[1].genre_ids == (28, 12) and got[1].keywords == frozenset({"heist"})
    assert got[3].keywords == frozenset() and got[3].year is None
    c.get_many([1])
    loads = [q for q in c.queries if "any(%s)" not in q[0]]
    assert len(loads) == 1


def test_postgres_catalog_reloads_after_ttl() -> None:
    c = FakeCatalog(ROWS, refresh_seconds=0.0)
    c.get_many([1])
    c.get_many([1])
    assert len([q for q in c.queries if "any(%s)" not in q[0]]) == 2


def test_unknown_ids_fetched_on_demand_and_kept() -> None:
    new = (9, "Fresh", 2026, [35], [], 50.0, 7.0, 100)
    c = FakeCatalog(ROWS, extra_rows=[new])
    assert set(c.get_many([1, 9, 404])) == {1, 9}
    lookups = [q for q in c.queries if "any(%s)" in q[0]]
    assert lookups[0][1] == ([9, 404],)
    c.get_many([9])  # now cached
    assert len([q for q in c.queries if "any(%s)" in q[0]]) == 1


def test_database_failure_degrades_to_empty() -> None:
    c = FakeCatalog(ROWS, fail=True)
    assert c.get_many([1, 2]) == {}
    assert c.all_ids() == [] and c.well_known(5) == []


def test_well_known_orders_by_votes() -> None:
    c = FakeCatalog(ROWS)
    assert [m.movie_id for m in c.well_known(2)] == [1, 2]


def _movie(i: int, popularity: float, avg: float, votes: int) -> MovieFeatures:
    return MovieFeatures(i, str(i), 2000, (18,), frozenset(), popularity, avg, votes)


def test_prior_rewards_quality_and_popularity_and_punishes_obscurity() -> None:
    tail = [_movie(100 + i, 1.0, 6.0, 5) for i in range(400)]  # more popular than the gem
    hit = _movie(1, 900.0, 8.5, 30000)
    meh = _movie(2, 800.0, 5.0, 30000)
    obscure_gem = _movie(3, 0.2, 9.5, 3)
    InMemoryCatalog([hit, meh, obscure_gem, *tail])
    assert hit.prior > meh.prior > obscure_gem.prior
    # Few votes: a 9.5 average barely moves the shrunk average.
    assert obscure_gem.prior < -cat.OBSCURITY_PENALTY / 2


def test_prior_handles_missing_stats() -> None:
    m = MovieFeatures(1, "x", None, (), frozenset(), None, None, None)
    InMemoryCatalog([m])
    assert m.prior == pytest.approx(-cat.OBSCURITY_PENALTY - cat.POPULARITY_WEIGHT * 0.5)


def test_taste_from_rows() -> None:
    rows = [
        ("rating", 10, 9, 0),
        ("fav", 20, 10, 2),
        ("fav", 21, 10, 1),
        ("rating", 11, 10, 0),
        ("rating", 12, 2, 0),
        ("rating", 13, 6, 0),
        ("least", 30, 1, 1),
        ("dismiss", 40, 0, 0),
        ("rating", 21, 9, 0),  # a favourite also rated: listed once
    ]
    t = taste_from_rows(rows)
    assert t.loved == [21, 20, 11, 10]
    assert t.disliked == {12, 30}
    assert t.dismissed == {40}
    assert t.n_signals == 8  # 5 ratings + 2 favourites + 1 least favourite


def test_taste_store_failure_is_empty() -> None:
    class Broken(PostgresTasteStore):
        def _query(self, user_id):
            raise RuntimeError("db down")

    assert Broken("postgresql://fake").taste(USER) is EMPTY_TASTE


def test_warm_loads_in_background_and_first_request_reuses_it() -> None:
    c = FakeCatalog(ROWS)
    c.warm()
    assert set(c.get_many([1, 2])) == {1, 2}
    assert len([q for q in c.queries if "any(%s)" not in q[0]]) == 1


def test_stale_catalog_refreshes_in_background_without_blocking() -> None:
    import threading

    gate = threading.Event()

    class Slow(FakeCatalog):
        def _query(self, sql, params=()):
            if self.queries and "any(%s)" not in sql:
                gate.wait(5)  # the refresh hangs until released
            return super()._query(sql, params)

    c = Slow(ROWS, refresh_seconds=0.0)
    c._background_refresh = True
    assert set(c.get_many([1])) == {1}  # first load (synchronous)
    assert set(c.get_many([1])) == {1}  # stale: refresh starts, old data served at once
    gate.set()
