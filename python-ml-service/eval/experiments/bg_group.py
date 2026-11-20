"""bg_*: group-recommendation variants aimed at big groups and the least-happy member.

Eval-only subclass of hybrid-v3's ContentRecommender; nothing in app/ changes. The ranking is

    rank_on_i = (1 - lam(n)) * personal_i + lam(n) * merged + G(n) * mean prior
              - GUARD_W * sum_j max(0, GUARD_Z - z_j)          (optional misery guard)

then the strategy aggregates, optionally followed by a coverage pass for big groups. Knobs are
class attributes; factories at the bottom set them.

    --recommender eval.experiments.bg_group:<factory>

Factories: baseline (no knobs = production group code), friend_big_off (merged-profile friend
signal off for 6+ members), pair_alike (+ pairs alike only at cosine >= 0.5), soft_misery
(friend_big_off + least_misery / closeness_weighted on the bottom-half mean for 3+ members),
soft_misery_pair (soft_misery + pair_alike; best). Screening scripts and logs (2026-11-19) are
outside the repo; most other knobs (size-aware lam/G, misery guard, coverage pass, sub-group
profiles, log-mean-exp mean, merged-profile construction) moved MovieLens groups < 1%.
"""

from __future__ import annotations

import numpy as np

from app.models.contract import (
    Strategy,
    GroupRecommendation,
    GroupRecommendationRequest,
    GroupRecommendationResponse,
)
from app.services import content_recommender as cr
from app.services import group_strategies as gs
from app.services.catalog import InMemoryCatalog
from app.services.content_recommender import (
    EMPTY_TASTE,
    ContentRecommender,
    _explain_group,
    _interpretable,
    _out,
)
from app.services.group_profile import merge_tastes, merge_vectors, rating_cosine
from app.services.group_strategies import aggregate, normalize, weighted_mean
from app.services import social
from app.services.social import group_weight_scale


class BigGroupRec(ContentRecommender):
    # Size-aware merge/prior weights: groups with n >= BIG_N use BIG_LAM / BIG_G (None = base).
    BIG_N = 99
    BIG_LAM: float | None = None
    BIG_G: float | None = None
    # Alike threshold per size: pairs may use their own.
    PAIR_ALIKE: float | None = None
    ALIKE_BIG: float | None = None  # alike threshold for n >= BIG_N
    # lam as a linear function of size beyond 2 (added to the base lam, capped at 1).
    LAM_SLOPE = 0.0
    # Misery guard: per member z-score of their own personal predicted score over the
    # candidates; penalty GUARD_W per unit below GUARD_Z, summed over members (or the min).
    GUARD_W = 0.0
    GUARD_Z = -1.0
    GUARD_MIN_N = 2
    GUARD_MODE = "sum"  # "sum" | "min" | "mean"
    # Coverage pass (n >= COVER_MIN_N): make sure every member has >= 1 of their own top
    # COVER_DEPTH candidates in the list, swapping out the list's tail.
    COVER_MIN_N = 99
    COVER_DEPTH = 20
    COVER_KEEP = 5  # never swap out the first COVER_KEEP picks
    # Merged profile for big groups: drop members far from the group (outliers) from merging.
    OUTLIER_DROP = 0.0  # drop members whose mean cosine to the rest is below this (big only)
    # Soft least misery for n >= SOFT_MIN_N: least_misery / closeness_weighted use the mean of
    # the bottom ceil(SOFT_MIN_Q * n) members instead of the single minimum.
    SOFT_MIN_N = 99
    SOFT_MIN_Q = 0.34
    # Fairness depth per k for n >= BIG_N (None = gs.FAIR_DEPTH_PER_K).
    BIG_FAIR_DEPTH: int | None = None
    # Friend signal for n >= FRIEND_BIG_N: scale on members' own and on the merged profile's.
    FRIEND_BIG_N = 99
    BIG_MEMBER_FRIEND = 1.0
    BIG_MERGED_FRIEND = 1.0
    # Merged friend signal decays as (REF/n)^(1 + FRIEND_POW) beyond social.GROUP_SIZE_REF
    # instead of REF/n (0 = production).
    FRIEND_POW = 0.0
    # MEAN strategy as log-mean-exp (soft max, b > 0: expected hits favour each member's top
    # picks) for n >= LME_MIN_N; CW uses it as its "mean" part when LME_CW.
    LME_B = 0.0
    LME_MIN_N = 2
    LME_CW = False
    # Members' own personal part as a rank-percentile mix (own top picks count more).
    # Merged weight scaled by group agreement instead of a hard threshold.
    SOFT_ALIKE = False
    # Merged-profile construction: loved list capped (interleaved, so each member's best stay),
    # disliked only if >= MERGE_DISLIKE_MIN members disliked it, per-movie rating combination
    # "mean" (prod) / "sqrt" (sum / sqrt(count): agreement counts more).
    MERGE_LOVED_CAP = 0
    MERGE_LOVED_PER_N = 0  # cap = MERGE_LOVED_PER_N * n (0 = off)
    MERGE_DISLIKE_MIN = 1
    MERGE_MODE = "mean"
    # Sub-group merged profiles for n >= SUB_N: members split into SUB_K taste clusters (rating
    # cosine); each member's merged term = SUB_A * their cluster's merged + (1-SUB_A) * group's.
    SUB_N = 99
    SUB_K = 2
    SUB_A = 0.0
    SOFT_LO = 0.1
    SOFT_HI = 0.3

    def recommend_for_group(self, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        inp = self._inputs(req)
        if inp is None:
            return GroupRecommendationResponse(results=[])
        return self._rank(inp, req)

    def _inputs(self, req: GroupRecommendationRequest) -> dict | None:
        """Everything strategy-independent (cacheable by the screening script)."""
        movies = self._movies(req.candidate_movie_ids, req.exclude_movie_ids)
        if not movies:
            return None
        tastes = [
            self.tastes.taste(m.user_id) if m.vector is not None else EMPTY_TASTE
            for m in req.members
        ]
        data = self.friends.data()
        member_ids = [m.user_id for m in req.members]
        fbig = sum(1 for m in req.members if m.weight > 0) >= self.FRIEND_BIG_N
        member_scored = [
            self.score(
                m.vector,
                t,
                movies,
                friend_trust=data.user_trust(m.user_id, exclude=member_ids)
                if data is not None and m.vector is not None
                else None,
                friend_scale=self.BIG_MEMBER_FRIEND if fbig else 1.0,
            )
            for m, t in zip(req.members, tastes, strict=True)
        ]
        scores = np.stack([s.predicted for s in member_scored])
        prior = np.stack([s.prior for s in member_scored])
        weights = np.array([m.weight for m in req.members], dtype=float)
        vectors = [_interpretable(m.vector) if m.vector is not None else None for m in req.members]

        counted = [j for j, w in enumerate(weights) if w > 0] or list(range(len(req.members)))
        n = len(counted)
        big = n >= self.BIG_N
        group_tastes = [tastes[j] for j in counted]
        cos = rating_cosine([t.ratings for t in group_tastes])
        merge_idx = counted
        if big and self.OUTLIER_DROP > 0 and n >= 3:
            merge_idx = self._core(counted, tastes)
        counted_ids = [member_ids[j] for j in merge_idx if req.members[j].vector is not None]
        merged = self.score(
            merge_vectors(vectors[j] for j in merge_idx),
            self._merge([tastes[j] for j in merge_idx]),
            movies,
            friend_trust=data.group_trust(counted_ids)
            if data is not None and counted_ids
            else None,
            friend_scale=group_weight_scale(len(counted_ids))
            * (self.BIG_MERGED_FRIEND if fbig else 1.0)
            * self._friend_decay(len(counted_ids)),
        )
        sub = None
        if n >= self.SUB_N:
            clusters = self._clusters(counted, tastes)
            sub = np.zeros((len(req.members), len(movies)))
            for cl in clusters:
                ids = [member_ids[j] for j in cl if req.members[j].vector is not None]
                ms = self.score(
                    merge_vectors(vectors[j] for j in cl),
                    merge_tastes([tastes[j] for j in cl]),
                    movies,
                    friend_trust=data.group_trust(ids) if data is not None and ids else None,
                    friend_scale=group_weight_scale(len(ids)),
                )
                for j in cl:
                    sub[j] = ms.predicted - ms.prior
        return dict(
            sub=sub,
            movies=movies,
            member_scored=member_scored,
            scores=scores,
            prior=prior,
            weights=weights,
            vectors=vectors,
            counted=counted,
            cos=cos,
            merged=merged,
        )

    def _rank(self, inp: dict, req: GroupRecommendationRequest) -> GroupRecommendationResponse:
        movies, member_scored = inp["movies"], inp["member_scored"]
        scores, prior, weights = inp["scores"], inp["prior"], inp["weights"]
        vectors, counted, cos, merged = inp["vectors"], inp["counted"], inp["cos"], inp["merged"]
        n = len(counted)
        big = n >= self.BIG_N
        thr = cr.ALIKE_RATING_COSINE
        if n == 2 and self.PAIR_ALIKE is not None:
            thr = self.PAIR_ALIKE
        if big and self.ALIKE_BIG is not None:
            thr = self.ALIKE_BIG
        alike = cos >= thr
        base_lam = self.BIG_LAM if big and self.BIG_LAM is not None else cr.GROUP_MERGE_WEIGHT
        base_g = self.BIG_G if big and self.BIG_G is not None else cr.GROUP_PRIOR_WEIGHT
        base_lam = min(1.0, base_lam + self.LAM_SLOPE * max(0, n - 2))
        if self.SOFT_ALIKE:
            t = float(np.clip((cos - self.SOFT_LO) / (self.SOFT_HI - self.SOFT_LO), 0.0, 1.0))
            lam = base_lam + t * (1.0 - base_lam)
            g = base_g + t * (1.0 - base_g)
        else:
            lam = 1.0 if alike else base_lam
            g = 1.0 if alike else base_g

        own = scores - prior
        mrow = (merged.predicted - merged.prior)[None, :]
        if inp.get("sub") is not None and self.SUB_A > 0:
            mrow = self.SUB_A * inp["sub"] + (1 - self.SUB_A) * mrow
        personal = (1 - lam) * own + lam * mrow
        rank_on = personal + g * weighted_mean(prior, weights)[None, :]

        if self.GUARD_W > 0 and n >= self.GUARD_MIN_N and own.shape[1] > 2:
            mu = own.mean(axis=1, keepdims=True)
            sd = own.std(axis=1, keepdims=True)
            z = (own - mu) / np.where(sd > 1e-9, sd, 1.0)
            short = np.clip(self.GUARD_Z - z[counted], 0.0, None)
            if self.GUARD_MODE == "min":
                pen = short.max(axis=0)
            elif self.GUARD_MODE == "mean":
                pen = short.mean(axis=0)
            else:
                pen = short.sum(axis=0)
            rank_on = rank_on - self.GUARD_W * pen[None, :]

        ranked = self._aggregate(req, normalize(rank_on), weights, n, big)
        if n >= self.COVER_MIN_N:
            ranked = self._cover(ranked, own + prior, rank_on, weights, counted)

        mean_scores = weighted_mean(scores, weights)
        results = []
        for i, _ in ranked:
            results.append(
                GroupRecommendation(
                    movie_id=movies[i].movie_id,
                    group_score=_out(mean_scores[i]),
                    member_scores={
                        m.user_id: _out(scores[j, i]) for j, m in enumerate(req.members)
                    },
                    explanation=_explain_group(
                        movies[i], scores[:, i], weights, vectors, member_scored, i
                    )
                    if member_scored is not None
                    else "",
                )
            )
        return GroupRecommendationResponse(results=results)

    def _lme(self, rank_on, weights):
        b = self.LME_B
        mx = rank_on.max(axis=0, keepdims=True)
        w = weights if weights.sum() > 0 else np.ones_like(weights)
        e = (w[:, None] * np.exp(b * (rank_on - mx))).sum(axis=0) / w.sum()
        return mx[0] + np.log(e) / b

    def _aggregate(self, req, rank_on, weights, n, big):
        st = req.strategy
        if self.LME_B and n >= self.LME_MIN_N:
            if st == Strategy.MEAN:
                return gs._top(self._lme(rank_on, weights), req.k)
            if st == Strategy.CLOSENESS_WEIGHTED and self.LME_CW:
                c = gs.group_closeness(req.closeness, rank_on.shape[0])
                low = gs._active(rank_on, weights).min(axis=0)
                return gs._top(c * self._lme(rank_on, weights) + (1 - c) * low, req.k)
        if n >= self.SOFT_MIN_N and st in (Strategy.LEAST_MISERY, Strategy.CLOSENESS_WEIGHTED):
            act = gs._active(rank_on, weights)
            m = max(1, int(np.ceil(self.SOFT_MIN_Q * act.shape[0])))
            soft = np.sort(act, axis=0)[:m].mean(axis=0)
            mean = weighted_mean(rank_on, weights)
            if st == Strategy.LEAST_MISERY:
                val = soft + gs._TIE * mean
            else:
                c = gs.group_closeness(req.closeness, rank_on.shape[0])
                val = c * mean + (1 - c) * soft
            return gs._top(val, req.k)
        if big and self.BIG_FAIR_DEPTH is not None and st == Strategy.FAIRNESS:
            old = gs.FAIR_DEPTH_PER_K
            gs.FAIR_DEPTH_PER_K = self.BIG_FAIR_DEPTH
            try:
                return aggregate(st, rank_on, weights, req.closeness, req.k)
            finally:
                gs.FAIR_DEPTH_PER_K = old
        return aggregate(st, rank_on, weights, req.closeness, req.k)

    def _merge(self, tastes):
        m = merge_tastes(tastes)
        cap = self.MERGE_LOVED_CAP or self.MERGE_LOVED_PER_N * len(tastes)
        if cap and len(m.loved) > cap:
            m.loved = m.loved[:cap]
        if self.MERGE_DISLIKE_MIN > 1:
            cnt: dict[int, int] = {}
            for t in tastes:
                for x in t.disliked:
                    cnt[x] = cnt.get(x, 0) + 1
            m.disliked = {x for x in m.disliked if cnt.get(x, 0) >= self.MERGE_DISLIKE_MIN}
        if self.MERGE_MODE != "mean":
            per: dict[int, list[float]] = {}
            means = []
            for t in tastes:
                if not t.ratings:
                    continue
                mu = float(np.mean(list(t.ratings.values())))
                means.append(mu)
                for x, v in t.ratings.items():
                    per.setdefault(x, []).append(v - mu)
            base = float(np.mean(means)) if means else 0.0
            m.ratings = {
                x: float(np.clip(base + sum(v) / np.sqrt(len(v)), 1.0, 10.0))
                for x, v in per.items()
            }
        return m

    def _friend_decay(self, n: int) -> float:
        ref = social.GROUP_SIZE_REF
        if not self.FRIEND_POW or not ref or n <= ref:
            return 1.0
        return (ref / n) ** self.FRIEND_POW

    def _clusters(self, counted: list[int], tastes) -> list[list[int]]:
        """Greedy split into SUB_K taste clusters: seeds = mutually least similar members, the
        rest joins the cluster it is most similar to on average (rating cosine)."""
        m = len(counted)
        sim = np.eye(m)
        for a in range(m):
            for b in range(a + 1, m):
                c = rating_cosine([tastes[counted[a]].ratings, tastes[counted[b]].ratings])
                sim[a, b] = sim[b, a] = c
        k = min(self.SUB_K, m)
        seeds = [int(np.argmin(sim.sum(axis=1)))]
        while len(seeds) < k:
            seeds.append(
                int(np.argmin(sim[:, seeds].max(axis=1) + 10 * np.isin(np.arange(m), seeds)))
            )
        groups = [[s] for s in seeds]
        rest = [a for a in range(m) if a not in seeds]
        rest.sort(key=lambda a: -max(sim[a, s] for s in seeds))
        for a in rest:
            best = max(range(k), key=lambda g: np.mean([sim[a, b] for b in groups[g]]))
            groups[best].append(a)
        return [[counted[a] for a in g] for g in groups]

    def _core(self, counted: list[int], tastes) -> list[int]:
        keep = []
        for j in counted:
            others = [tastes[o].ratings for o in counted if o != j]
            c = np.mean([rating_cosine([tastes[j].ratings, r]) for r in others])
            if c >= self.OUTLIER_DROP:
                keep.append(j)
        return keep if len(keep) >= 2 else counted

    def _cover(self, ranked, own_scores, rank_on, weights, counted):
        if not ranked:
            return ranked
        n_movies = own_scores.shape[1]
        depth = min(self.COVER_DEPTH, n_movies)
        order = np.argsort(-own_scores, axis=1, kind="stable")[:, :depth]
        tops = {j: set(order[j].tolist()) for j in counted}
        picks = [i for i, _ in ranked]
        mean = weighted_mean(rank_on, weights)
        covered = {j for j in counted if tops[j] & set(picks)}
        slot = len(picks) - 1
        # Members in order of how badly the list serves them (lowest mean own score first).
        uncovered = sorted(
            (j for j in counted if j not in covered),
            key=lambda j: own_scores[j, picks].mean(),
        )
        for j in uncovered:
            if j in covered or slot < self.COVER_KEEP:
                continue
            cands = [c for c in order[j] if c not in picks]
            if not cands:
                continue

            # Prefer a pick that also covers other uncovered members, then group appeal.
            def value(c, covered=covered):
                extra = sum(1 for o in uncovered if o not in covered and c in tops[o])
                return (extra, mean[c])

            best = max(cands, key=value)
            # Don't drop an item that is the only cover for someone.
            while slot >= self.COVER_KEEP:
                victim = picks[slot]
                sole = any(victim in tops[o] and len(tops[o] & set(picks)) == 1 for o in covered)
                if not sole:
                    break
                slot -= 1
            if slot < self.COVER_KEEP:
                break
            picks[slot] = int(best)
            slot -= 1
            covered |= {o for o in counted if best in tops[o]}
        return [(i, float(mean[i])) for i in picks]


def _make(**knobs) -> BigGroupRec:
    cls = type("BG", (BigGroupRec,), knobs)
    return cls(InMemoryCatalog([]))


def baseline():
    return _make()


def friend_big_off():
    """Merged-profile friend signal off for groups of 6+ (persona big groups)."""
    return _make(FRIEND_BIG_N=6, BIG_MERGED_FRIEND=0.0)


def pair_alike():
    """friend_big_off + pairs count as like-minded only at rating cosine >= 0.5."""
    return _make(FRIEND_BIG_N=6, BIG_MERGED_FRIEND=0.0, PAIR_ALIKE=0.5)


def soft_misery():
    """friend_big_off + soft least misery for 3+ members: least_misery and closeness_weighted
    use the mean of the bottom half of members (ceil(n/2)) instead of the single minimum."""
    return _make(FRIEND_BIG_N=6, BIG_MERGED_FRIEND=0.0, SOFT_MIN_N=3, SOFT_MIN_Q=0.5)


def soft_misery_pair():
    """soft_misery + pairs count as like-minded only at rating cosine >= 0.5 (not 0.3)."""
    return _make(
        FRIEND_BIG_N=6, BIG_MERGED_FRIEND=0.0, SOFT_MIN_N=3, SOFT_MIN_Q=0.5, PAIR_ALIKE=0.5
    )
