"""CF experiment: explicit "sum" item-kNN plus a bonus from implicit (who-watched-what) item-kNN.

pred = explicit_pred + W * f(implicit_evidence), where implicit_evidence is the sum of binary-
co-occurrence cosine similarities to everything the user rated, and f = log1p (diminishing) or
evidence / (evidence + C) (saturating). Support (which drives beta) comes from the explicit model,
or optionally max(explicit, SUPPORT_MIX * implicit) so implicit-only evidence can still switch CF on.

Factories (pass with --recommender eval.experiments.cf_blend:<name>):
  make_w05, make_w1, make_w2 (log1p bonus, explicit support), make_w1_sup (also implicit support).
"""

from __future__ import annotations

import numpy as np

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog


class BlendKNN:
    def __init__(
        self,
        triples,
        w: float = 1.0,
        f: str = "log",
        c: float = 2.0,
        support_mix: float = 0.0,
        explicit: dict | None = None,
        implicit: dict | None = None,
    ) -> None:
        triples = list(triples)
        self.exp = collaborative.ItemKNN(triples, **({"mode": "sum"} | (explicit or {})))
        self.imp = collaborative.ItemKNN(
            triples, **({"mode": "implicit", "sum_scale": 1.0} | (implicit or {}))
        )
        self.items = self.exp.items
        self.w, self.f, self.c, self.support_mix = w, f, c, support_mix

    def predict(self, user_ratings, item_ids):
        pred, support, top = self.exp.predict(user_ratings, item_ids)
        ipred, isup, _ = self.imp.predict(user_ratings, item_ids)
        evidence = np.maximum(ipred - collaborative.NEUTRAL_IMPLICIT, 0.0)
        # ipred is clipped at 10; recompute from support for unclipped evidence (sims >= 0 here).
        evidence = np.where(ipred >= 10.0, isup, evidence)
        bonus = np.log1p(evidence) if self.f == "log" else evidence / (evidence + self.c)
        if self.support_mix > 0:
            # where explicit had no evidence, pred is the user's mean (or 0 for unknown items)
            mean = float(np.mean(list(user_ratings.values()))) if user_ratings else 0.0
            pred = np.where(support > 0, pred, mean)
            support = np.maximum(support, self.support_mix * isup)
        return pred + self.w * bonus, support, top


def _factory(**kw):
    def make():
        content_recommender.CF_MODELS["knn"] = lambda t: BlendKNN(t, **kw)
        return content_recommender.ContentRecommender(InMemoryCatalog([]))

    return make


make_w05 = _factory(w=0.5)
make_w1 = _factory(w=1.0)
make_w2 = _factory(w=2.0)
make_w1_sup = _factory(w=1.0, support_mix=0.2)
make_sat2 = _factory(w=2.0, f="sat", c=2.0)
make_w3 = _factory(w=3.0)
make_w2_k100 = _factory(w=2.0, explicit={"k": 100}, implicit={"k": 100})
make_w2_ik200 = _factory(w=2.0, implicit={"k": 200})
