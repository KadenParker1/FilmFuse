"""so_social: privacy-respecting friend signal for users AND for the group's merged profile.

Builds on ct_social.SocialRecommender (experiment only):

    social(m) = W_LIKE  * sum_f t_f (r_fm - mean_f) / (sum_f t_f [f rated m] + SHRINK)
              + W_WATCH * sum_f t_f [f rated m]     / (sum_f t_f [f rated m] + SHRINK)

Privacy: a friend's rating feeds the signal only if core-api would show it to the viewer
(core-api RatingVisibilitySql.visibleTo): visibility PUBLIC or FRIENDS, source not
'onboarding_swipe', author not suspended, and no block either way between viewer and author.
PRIVATE ratings never count, not even through the friend's mean (centred on the mean of the
friend's *visible* train ratings). VISIBLE_ONLY=False reproduces the leaky round-1 behaviour.

Groups: recommend_for_group scores a merged pseudo-member (group_profile.merge_tastes) that has no
friends of its own. Here it gets the members' friends: trust t_f = (sum over counted members u of
closeness(u, f)) / n_members, so a friend of several members counts more. With EXCLUDE_GROUP the
members themselves are excluded (their ratings are already pooled in the merged profile) both for
the merged profile and for each member's own social term while scoring the group.
GROUP_PUBLIC_ONLY_UNLESS_SHARED: in a group, a FRIENDS-only rating of f is used only if f is a
friend of every counted member (others see the group's list too); otherwise only f's PUBLIC ones.

Factories: --recommender eval.experiments.so_social:<name>
"""

from __future__ import annotations

import os
from collections import defaultdict
from uuid import UUID

import numpy as np

from app.models.contract import GroupRecommendationRequest, GroupRecommendationResponse
from app.services.catalog import EMPTY_TASTE, InMemoryCatalog, MovieFeatures, UserTaste
from app.services.content_recommender import REJECTED_SCORE, Scored
from eval.experiments.ct_social import SocialRecommender

FRIEND_VISIBLE = ("PUBLIC", "FRIENDS")


def _load_visibility() -> tuple[dict[UUID, dict[int, str]], set[UUID], set[tuple[UUID, UUID]]]:
    """(author -> movie -> visibility for non-swipe ratings, suspended users, blocked pairs)."""
    url = os.environ.get("ML_DATABASE_URL")
    vis: dict[UUID, dict[int, str]] = defaultdict(dict)
    if not url:
        return {}, set(), set()
    import psycopg

    with psycopg.connect(url, connect_timeout=5) as conn, conn.cursor() as cur:
        cur.execute(
            "select user_id, movie_id, visibility from app.ratings"
            " where source <> 'onboarding_swipe'"
        )
        for u, m, v in cur.fetchall():
            vis[u][m] = v
        cur.execute("select id from app.profiles where status = 'SUSPENDED'")
        suspended = {r[0] for r in cur.fetchall()}
        cur.execute("select blocker_id, blocked_id from app.blocks")
        blocks = set()
        for a, b in cur.fetchall():
            blocks |= {(a, b), (b, a)}
    return dict(vis), suspended, blocks


class SoSocial(SocialRecommender):
    W = 2.0  # liked component (W_LIKE)
    W_WATCH = 0.0  # exposure component
    VISIBLE_ONLY = True
    GROUP_SOCIAL = True  # give the merged profile a friend signal
    GROUP_W = None  # W for the merged profile (None = same as W)
    EXCLUDE_GROUP = True  # in a group, drop co-members from the friend signal
    MEMBER_SOCIAL_IN_GROUP = True  # keep members' own social term while scoring a group
    GROUP_PUBLIC_ONLY_UNLESS_SHARED = False
    GROUP_SIZE_REF = 0  # >0: scale the merged-profile weight by REF/n for groups bigger than REF

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._vis_loaded = False
        self._vis: dict[UUID, dict[int, str]] = {}
        self._suspended: set[UUID] = set()
        self._blocks: set[tuple[UUID, UUID]] = set()
        self._public: dict[UUID, set[int]] = {}
        self._group: list[UUID] | None = None

    def train_on(self, train: dict[UUID, list[tuple[int, int, float]]]) -> None:
        super().train_on(train)
        if not self._vis_loaded:
            self._vis, self._suspended, self._blocks = _load_visibility()
            self._vis_loaded = True
        self._public = {}
        if self.VISIBLE_ONLY:
            centred = {}
            for u, events in train.items():
                vis = self._vis.get(u, {})
                ok = [(m, s) for m, s, _ in events if vis.get(m) in FRIEND_VISIBLE]
                if u in self._suspended or not ok:
                    continue
                mean = sum(s for _, s in ok) / len(ok)
                centred[u] = {m: s - mean for m, s in ok}
                self._public[u] = {m for m, _ in ok if vis.get(m) == "PUBLIC"}
            self._centred = centred
            for u, trust in list(self._trust.items()):
                kept = {
                    f: t for f, t in trust.items() if f in centred and (u, f) not in self._blocks
                }
                if kept:
                    self._trust[u] = kept
                else:
                    del self._trust[u]

    def _signal(
        self, trust: dict[UUID, float], movies: list[MovieFeatures], w: float, public_only=()
    ) -> np.ndarray:
        n = len(movies)
        num, den, seen = np.zeros(n), np.full(n, self.SHRINK), np.zeros(n)
        for f, t in trust.items():
            ratings = self._centred.get(f)
            if not ratings:
                continue
            pub = self._public.get(f, set()) if f in public_only else None
            for i, m in enumerate(movies):
                r = ratings.get(m.movie_id)
                if r is None or (pub is not None and m.movie_id not in pub):
                    continue
                num[i] += t * r
                den[i] += t
                seen[i] += t
        return w * num / den + self.W_WATCH * seen / den

    def social(self, user: UUID, movies: list[MovieFeatures]) -> np.ndarray:
        trust = self._trust.get(user, {})
        if self._group is not None:
            if not self.MEMBER_SOCIAL_IN_GROUP:
                return np.zeros(len(movies))
            if self.EXCLUDE_GROUP:
                trust = {f: t for f, t in trust.items() if f not in self._group}
        return self._signal(trust, movies, self.W)

    def group_social(self, movies: list[MovieFeatures]) -> np.ndarray:
        members = self._group or []
        acc: dict[UUID, float] = defaultdict(float)
        friends_of: dict[UUID, int] = defaultdict(int)
        for u in members:
            for f, t in self._trust.get(u, {}).items():
                if self.EXCLUDE_GROUP and f in members:
                    continue
                if any((v, f) in self._blocks for v in members):
                    continue  # blocked by someone who will see the list
                acc[f] += t / len(members)
                friends_of[f] += 1
        public_only = (
            {f for f, c in friends_of.items() if c < len(members)}
            if self.GROUP_PUBLIC_ONLY_UNLESS_SHARED
            else set()
        )
        w = self.W if self.GROUP_W is None else self.GROUP_W
        if self.GROUP_SIZE_REF and len(members) > self.GROUP_SIZE_REF:
            w *= self.GROUP_SIZE_REF / len(members)  # big groups: union of friends dilutes
        return self._signal(dict(acc), movies, w, public_only)

    def score(self, vector, taste: UserTaste, movies: list[MovieFeatures]) -> Scored:
        scored = super(SocialRecommender, self).score(vector, taste, movies)
        if not movies:
            return scored
        user = self._owner.get(id(taste))
        if user is not None and user in self._trust:
            boost = self.social(user, movies)
        elif (
            user is None
            and taste is not EMPTY_TASTE
            and self._group is not None
            and self.GROUP_SOCIAL
        ):
            boost = self.group_social(movies)  # the merged pseudo-member
        else:
            return scored
        rejected = scored.predicted == REJECTED_SCORE
        scored.predicted = np.where(rejected, scored.predicted, scored.predicted + boost)
        return scored

    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        counted = [m for m in req.members if m.weight > 0] or list(req.members)
        self._group = [m.user_id for m in counted if m.vector is not None]
        try:
            return super().recommend_for_group(req)
        finally:
            self._group = None


def _factory(**attrs):
    def make() -> SoSocial:
        rec = SoSocial(InMemoryCatalog([]))
        for k, v in attrs.items():
            setattr(rec, k, v)
        return rec

    return make


# Round-1 equivalent but visibility-respecting, no group signal.
vis_w2_nogroup = _factory(W=2.0, GROUP_SOCIAL=False, EXCLUDE_GROUP=False)
# Leaky reference (all visibilities), no group signal = ct_social:make_w2 on this code path.
leaky_w2_nogroup = _factory(W=2.0, VISIBLE_ONLY=False, GROUP_SOCIAL=False, EXCLUDE_GROUP=False)
# Main: visibility + merged-profile friend signal from friends outside the group.
vis_w2 = _factory(W=2.0)
vis_w1 = _factory(W=1.0)
vis_w3 = _factory(W=3.0)
vis_w2_allfriends = _factory(W=2.0, EXCLUDE_GROUP=False)
vis_w2_watch05 = _factory(W=2.0, W_WATCH=0.5)
vis_w2_watch1 = _factory(W=2.0, W_WATCH=1.0)
vis_watch_only = _factory(W=0.0, W_WATCH=1.0)
vis_w2_strict = _factory(W=2.0, GROUP_PUBLIC_ONLY_UNLESS_SHARED=True)
vis_w2_g3 = _factory(W=2.0, GROUP_W=3.0)
vis_w2_g4 = _factory(W=2.0, GROUP_W=4.0)


class SoSocialControl(SoSocial):
    """Control: every user's friends replaced by the same number of random other users (same
    trust values). If this gains as much, the effect is generic user-user CF, not friendship."""

    def train_on(self, train):
        super().train_on(train)
        import random

        rng = random.Random(7)
        pool = sorted(self._centred, key=str)
        for u, trust in list(self._trust.items()):
            others = [p for p in pool if p != u and p not in trust]
            self._trust[u] = dict(zip(rng.sample(others, len(trust)), trust.values(), strict=True))


def _factory2(cls, **attrs):
    def make():
        rec = cls(InMemoryCatalog([]))
        for k, v in attrs.items():
            setattr(rec, k, v)
        return rec

    return make


vis_combo = _factory(W=2.0, W_WATCH=1.0, GROUP_W=4.0)
vis_combo_g3 = _factory(W=2.0, W_WATCH=1.0, GROUP_W=3.0)
vis_w2_g6 = _factory(W=2.0, GROUP_W=6.0)
control_random_w2_g4 = _factory2(SoSocialControl, W=2.0, GROUP_W=4.0)
# Most conservative privacy: only PUBLIC ratings ever feed anyone's friend signal.
FRIEND_VISIBLE_PUBLIC = ("PUBLIC",)


class SoSocialPublic(SoSocial):
    def train_on(self, train):
        global FRIEND_VISIBLE
        saved, FRIEND_VISIBLE = FRIEND_VISIBLE, FRIEND_VISIBLE_PUBLIC
        try:
            super().train_on(train)
        finally:
            FRIEND_VISIBLE = saved


public_w2_g4 = _factory2(SoSocialPublic, W=2.0, GROUP_W=4.0)

vis_combo_size4 = _factory(W=2.0, W_WATCH=1.0, GROUP_W=4.0, GROUP_SIZE_REF=4)
