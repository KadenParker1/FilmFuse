"""Friend signal: what a user's friends thought of a movie (approved by the owner 2026-11-19,
for personal and group recommendations, with the permissive group rule).

    signal(m) = LIKE  * sum_f t_f (r_fm - mean_f) / (sum_f t_f [f rated m] + SHRINK)
              + WATCH * sum_f t_f [f rated m]     / (sum_f t_f [f rated m] + SHRINK)

over friends f who rated movie m, with trust t_f = friend_closeness(user, f). "Like" is how much
friends liked it (their rating minus their own average), "watch" that friends watched it at all.

Privacy (mirrors core-api's RatingVisibilitySql.visibleTo, the rule for who may see a rating):
a rating feeds the signal only if its visibility is PUBLIC or FRIENDS, it isn't an onboarding
swipe, its author isn't suspended, and there is no block either way between the two users.
PRIVATE ratings never count, not even through the author's average (each friend is centred on
the mean of their *visible* ratings only). The signal never names who rated what.

Groups: the merged group profile gets the members' friends (trust = summed closeness / number of
members, so a friend of several members counts more), excluding the members themselves (their
ratings are already pooled) and anyone blocked by any member. Permissive rule (owner's choice):
a friend of one member can influence the list the whole group sees, aggregated and shrunk.

Found in round-1/4 experiments (eval/experiments/ct_social.py, so_social.py): on the persona
friend networks, personal accuracy +25% and group satisfaction about x2.3 (4 hold-out splits);
a random-friends control gained nothing. Synthetic personas: real gains will be smaller.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Protocol
from uuid import UUID

import numpy as np

log = logging.getLogger(__name__)

LIKE_WEIGHT = 2.0
WATCH_WEIGHT = 1.0
SHRINK = 1.0  # pseudo-trust: one friend's opinion counts only partly
GROUP_WEIGHT = 4.0  # both weights x2 for the merged group profile
GROUP_SIZE_REF = 4  # bigger groups: weight x REF/n (the union of friends dilutes the signal)
# Groups larger than this get no merged-profile friend signal at all (members keep their own):
# pooling 6-8 people's friends is noise (round 5: big persona groups +29% over 12 splits).
GROUP_MAX_MEMBERS = 5
FRIEND_VISIBLE = frozenset({"PUBLIC", "FRIENDS"})
HIDDEN_SOURCES = frozenset({"onboarding_swipe"})


@dataclass
class FriendGraph:
    """Who is friends with whom and what friends may see. Built from app.friend_closeness,
    app.blocks and app.profiles; `visibility` maps author -> movie -> (visibility, source)."""

    closeness: dict[UUID, dict[UUID, float]] = field(default_factory=dict)
    blocked: set[tuple[UUID, UUID]] = field(default_factory=set)  # both directions
    suspended: set[UUID] = field(default_factory=set)
    visibility: dict[UUID, dict[int, tuple[str, str]]] = field(default_factory=dict)


@dataclass
class FriendData:
    trust: dict[UUID, dict[UUID, float]]  # user -> usable friend -> closeness
    centred: dict[UUID, dict[int, float]]  # friend -> movie -> visible rating - visible mean
    blocked: set[tuple[UUID, UUID]]

    def user_trust(self, user: UUID, exclude: Iterable[UUID] = ()) -> dict[UUID, float]:
        skip = set(exclude)
        return {f: t for f, t in self.trust.get(user, {}).items() if f not in skip}

    def group_trust(self, members: list[UUID]) -> dict[UUID, float]:
        """Union of the members' friends: closeness summed and divided by the group size;
        members themselves and anyone blocked by any member are left out."""
        acc: dict[UUID, float] = defaultdict(float)
        for u in members:
            for f, t in self.trust.get(u, {}).items():
                if f in members or any((v, f) in self.blocked for v in members):
                    continue
                acc[f] += t / len(members)
        return dict(acc)


def build_friend_data(
    graph: FriendGraph, ratings: dict[UUID, Iterable[tuple[int, float]]]
) -> FriendData:
    """`ratings`: author -> (movie, score). Only ratings friends may see are kept (see module
    docstring); a rating with no visibility entry is treated as private."""
    centred: dict[UUID, dict[int, float]] = {}
    for author, events in ratings.items():
        if author in graph.suspended:
            continue
        vis = graph.visibility.get(author, {})
        visible = []
        for movie, score in events:
            v, source = vis.get(movie, ("PRIVATE", ""))
            if v in FRIEND_VISIBLE and source not in HIDDEN_SOURCES:
                visible.append((movie, float(score)))
        if visible:
            mean = sum(s for _, s in visible) / len(visible)
            centred[author] = {m: s - mean for m, s in visible}
    trust: dict[UUID, dict[UUID, float]] = {}
    for user, friends in graph.closeness.items():
        usable = {
            f: float(t)
            for f, t in friends.items()
            if t > 0 and f in centred and (user, f) not in graph.blocked
        }
        if usable:
            trust[user] = usable
    return FriendData(trust=trust, centred=centred, blocked=set(graph.blocked))


def friend_signal(
    data: FriendData,
    trust: dict[UUID, float],
    movie_ids: list[int],
    like: float = LIKE_WEIGHT,
    watch: float = WATCH_WEIGHT,
) -> np.ndarray:
    n = len(movie_ids)
    if not trust or n == 0:
        return np.zeros(n)
    position = {m: i for i, m in enumerate(movie_ids)}
    num, seen = np.zeros(n), np.zeros(n)
    for f, t in trust.items():
        for movie, c in data.centred.get(f, {}).items():
            i = position.get(movie)
            if i is not None:
                num[i] += t * c
                seen[i] += t
    den = seen + SHRINK
    return like * num / den + watch * seen / den


def group_weight_scale(n_members: int) -> float:
    if n_members > GROUP_MAX_MEMBERS:
        return 0.0
    scale = GROUP_WEIGHT / LIKE_WEIGHT
    if GROUP_SIZE_REF and n_members > GROUP_SIZE_REF:
        scale *= GROUP_SIZE_REF / n_members
    return scale


# ---- sources ---------------------------------------------------------------------------------


class FriendSource(Protocol):
    def data(self) -> FriendData | None: ...


class StaticFriendSource:
    def __init__(self, data: FriendData | None) -> None:
        self._data = data

    def data(self) -> FriendData | None:
        return self._data


def load_friend_graph(conninfo: str) -> tuple[FriendGraph, dict[UUID, list[tuple[int, float]]]]:
    """The friend graph plus every non-private rating, through the read-only ml_reader role."""
    import psycopg

    graph = FriendGraph()
    ratings: dict[UUID, list[tuple[int, float]]] = defaultdict(list)
    with psycopg.connect(conninfo, connect_timeout=5) as conn, conn.cursor() as cur:
        cur.execute("select user_id, friend_id, score from app.friend_closeness")
        closeness: dict[UUID, dict[UUID, float]] = defaultdict(dict)
        for a, b, s in cur.fetchall():
            closeness[a][b] = float(s)
        graph.closeness = dict(closeness)
        cur.execute("select blocker_id, blocked_id from app.blocks")
        for a, b in cur.fetchall():
            graph.blocked |= {(a, b), (b, a)}
        cur.execute("select id from app.profiles where status = 'SUSPENDED'")
        graph.suspended = {r[0] for r in cur.fetchall()}
        cur.execute(
            "select user_id, movie_id, score, visibility, source from app.ratings"
            " where visibility <> 'PRIVATE'"
        )
        vis: dict[UUID, dict[int, tuple[str, str]]] = defaultdict(dict)
        for u, m, s, v, source in cur.fetchall():
            vis[u][m] = (v, source)
            ratings[u].append((m, float(s)))
        graph.visibility = dict(vis)
    return graph, dict(ratings)


class PostgresFriendSource:
    """Friend data rebuilt in the background every `refresh_seconds` (like the CF model).
    Until the first build finishes, `data()` is None and there is no friend signal."""

    def __init__(self, conninfo: str, refresh_seconds: float = 3600.0, background: bool = True):
        self._conninfo = conninfo
        self._refresh_seconds = refresh_seconds
        self._background = background
        self._lock = threading.Lock()
        self._build_lock = threading.Lock()
        self._data: FriendData | None = None
        self._built_at = 0.0
        self._building = False

    def _load(self) -> FriendData:  # pragma: no cover - real database only
        return build_friend_data(*load_friend_graph(self._conninfo))

    def _build(self) -> None:
        with self._build_lock:
            try:
                data = self._load()
                with self._lock:
                    self._data = data
                log.info("friend data built: %d users with usable friends", len(data.trust))
            except Exception:
                log.exception("friend data build failed; recommending without the friend signal")
            finally:
                with self._lock:
                    self._built_at = time.monotonic()
                    self._building = False

    def warm(self) -> None:
        with self._lock:
            if self._building:
                return
            self._building = True
        threading.Thread(target=self._build, name="friends-warm", daemon=True).start()

    def data(self) -> FriendData | None:
        with self._lock:
            stale = time.monotonic() - self._built_at > self._refresh_seconds
            start = stale and not self._building
            if start:
                self._building = True
        if start:
            if self._background:
                threading.Thread(target=self._build, name="friends-refresh", daemon=True).start()
            else:
                self._build()
        with self._lock:
            return self._data
