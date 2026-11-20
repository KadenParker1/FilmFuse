"""MF weight split by role: individuals vs group members vs the merged-profile pseudo-member.

hybrid-v6's BlendKNN adds `mf_weight * z-scored iALS preference` to every CF prediction, and its
`predict(user_ratings, item_ids)` cannot tell a person from the merged group profile. This
subclass swaps the served model's `mf_weight` around each `score()` call according to who is
being scored:

* "user":   recommend_for_user (individual lists)            -> mf_user
* "member": a real member while scoring a group              -> mf_member
* "merged": the pooled-ratings pseudo-member (group_profile) -> mf_merged

In `recommend_for_group` the members are scored first (one `score()` call each) and the merged
profile last, so the call count tells them apart. The iALS model itself is trained once and is
identical for every weight (it is only skipped when the base weight is 0, which we avoid).
"""

from __future__ import annotations

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog
from app.services.content_recommender import ContentRecommender


class MFSplitRecommender(ContentRecommender):
    def __init__(
        self,
        catalog,
        mf_user: float = collaborative.MF_WEIGHT,
        mf_member: float | None = None,
        mf_merged: float | None = None,
        **kw,
    ) -> None:
        super().__init__(catalog, **kw)
        self.mf_user = mf_user
        self.mf_member = mf_user if mf_member is None else mf_member
        self.mf_merged = self.mf_member if mf_merged is None else mf_merged
        self._role = "user"
        self._group_calls = 0
        self._group_size = 0

    def train_on(self, train) -> None:
        # Make sure the iALS part is built (BlendKNN skips it when its weight is 0).
        saved = collaborative.MF_WEIGHT
        collaborative.MF_WEIGHT = max(saved, 1.0)
        try:
            super().train_on(train)
        finally:
            collaborative.MF_WEIGHT = saved

    def _weight(self) -> float:
        if self._role == "group":
            return self.mf_member if self._group_calls <= self._group_size else self.mf_merged
        return self.mf_user

    def score(self, *args, **kwargs):
        if self._role == "group":
            self._group_calls += 1
        model = self.ratings.model()
        if model is None or getattr(model, "mf", None) is None:
            return super().score(*args, **kwargs)
        saved = model.mf_weight
        model.mf_weight = self._weight()
        try:
            return super().score(*args, **kwargs)
        finally:
            model.mf_weight = saved

    def recommend_for_group(self, req):
        self._role, self._group_calls, self._group_size = "group", 0, len(req.members)
        try:
            return super().recommend_for_group(req)
        finally:
            self._role = "user"


def _factory(**kw):
    def make():
        content_recommender.CF_MODEL = "blend"
        return MFSplitRecommender(InMemoryCatalog([]), **kw)

    return make


def factory(**kw):
    """Ad-hoc screens: factory(mf_user=..., mf_member=..., mf_merged=...)."""
    return _factory(**kw)


# ---- named variants --------------------------------------------------------------------------
make_ref = _factory(mf_user=1.0)  # = hybrid-v6 (sanity check)
make_merged0 = _factory(mf_user=1.0, mf_member=1.0, mf_merged=0.0)  # hypothesis check
make_u2 = _factory(mf_user=2.0, mf_member=1.0, mf_merged=1.0)  # individuals 2, groups as v6
make_u15 = _factory(mf_user=1.5, mf_member=1.0, mf_merged=1.0)

# Findings (2026-11-19, full-size MovieLens screens on seed 42 and seed 7 / test-fraction 0.25):
# * merged-profile MF off (mf_merged 0) hurts groups on both samples (similar -1% / -11%);
# * mf_merged 2 hurt similar groups on seed 42 (-6%) but helped on seed 7 (+1%): noise;
# * mf_member 0..2 barely moves anything; mf_user 1..3 leaves full-mode NDCG flat (+-0.7%);
# * the merged profile's iALS ranking is NOT more popularity-like for similar groups
#   (Spearman with train counts 0.445 vs members 0.454; 0.84 with members), only for
#   random/dissimilar ones (0.52/0.60 vs 0.44/0.36). Keep one weight (1.0).
