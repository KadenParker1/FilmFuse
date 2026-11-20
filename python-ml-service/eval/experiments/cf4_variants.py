"""Round-4 CF experiments: variants of hybrid-v2's BlendKNN (explicit "sum" kNN + w*log1p(implicit)).

The implicit neighbour table is rebuilt here with a configurable similarity:
  "cosine": co / sqrt(n_i n_j)                       (hybrid-v2)
  "asym":   co / (n_i^(1-a) * n_j^a)                 (a > 0.5 penalises popular neighbours/candidates)
  "lift":   log(co * N / (n_i n_j)) clipped >= 0, weighted by co/(co+shrink)  (popularity-debiased PMI)
each shrunk by co/(co+shrink); evidence for candidate i = sum of sim(i, j) over the user's rated j.
Optionally the evidence is divided by pop_i^pop_alpha (candidate-popularity normalisation).

Pass with --recommender eval.experiments.cf4_variants:<factory>.
"""

from __future__ import annotations

import numpy as np

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog
from app.services.collaborative import ItemKNN, _Matrix


def implicit_knn(triples, k=100, shrink=10.0, min_item_ratings=3, sim="cosine", a=0.5):
    m = _Matrix(triples, min_item_ratings)
    knn = object.__new__(ItemKNN)
    knn.items, knn.index = m.items, m.index
    knn.mode, knn.sum_scale = "implicit", 1.0
    n = len(m.items)
    k = max(1, min(k, n - 1)) if n > 1 else 0
    knn.k = k
    knn.nb_idx = np.zeros((n, k), dtype=np.int32)
    knn.nb_sim = np.zeros((n, k), dtype=np.float32)
    knn.pop = m.rated.sum(axis=1)
    if n < 2 or k == 0:
        return knn
    pop = np.maximum(knn.pop, 1.0)
    n_users = m.rated.shape[1]
    for start in range(0, n, collaborative._BLOCK):
        stop = min(start + collaborative._BLOCK, n)
        co = m.rated[start:stop] @ m.rated.T  # rows: candidate i, cols: neighbour j
        damp = co / (co + shrink) if shrink > 0 else (co > 0)
        pi, pj = pop[start:stop, None], pop[None, :]
        if sim == "cosine":
            s = co / np.sqrt(pi * pj)
        elif sim == "asym":
            # row = candidate i: penalise popular candidates with exponent a
            s = co / (pi**a * pj ** (1 - a))
        elif sim == "lift":
            with np.errstate(divide="ignore"):
                s = np.log(np.maximum(co * n_users / (pi * pj), 1e-9))
            s = np.maximum(s, 0.0)
        else:
            raise ValueError(sim)
        s = (s * damp).astype(np.float32)
        s[np.arange(stop - start), np.arange(start, stop)] = 0.0
        top = np.argpartition(-s, k - 1, axis=1)[:, :k]
        knn.nb_idx[start:stop] = top
        knn.nb_sim[start:stop] = np.take_along_axis(s, top, axis=1)
    return knn


class Blend:
    def __init__(
        self,
        triples,
        w=2.0,
        k=100,
        ik=None,
        shrink=10.0,
        ishrink=None,
        mir=3,
        sim="cosine",
        a=0.5,
        pop_alpha=0.0,
        f="log",
    ):
        triples = list(triples)
        self.explicit = ItemKNN(triples, k=k, shrink=shrink, min_item_ratings=mir, mode="sum")
        self.implicit = implicit_knn(
            triples,
            k=ik or k,
            shrink=shrink if ishrink is None else ishrink,
            min_item_ratings=mir,
            sim=sim,
            a=a,
        )
        self.items = self.explicit.items
        self.w, self.pop_alpha, self.f = w, pop_alpha, f

    def predict(self, user_ratings, item_ids):
        pred, support, top = self.explicit.predict(user_ratings, item_ids)
        _, evidence, _ = self.implicit.predict(user_ratings, item_ids)
        if self.pop_alpha:
            pos = np.array([self.implicit.index.get(i, -1) for i in item_ids])
            pop = np.where(pos >= 0, self.implicit.pop[np.maximum(pos, 0)], 1.0)
            evidence = evidence / np.maximum(pop, 1.0) ** self.pop_alpha
        bonus = np.log1p(evidence) if self.f == "log" else evidence
        return pred + self.w * bonus, support, top


def _factory(**kw):
    def make():
        content_recommender.CF_MODELS["blend"] = lambda t: Blend(t, **kw)
        return content_recommender.ContentRecommender(InMemoryCatalog([]))

    return make


ref = _factory()  # should reproduce hybrid-v2
w1 = _factory(w=1.0)
w3 = _factory(w=3.0)
shrink0 = _factory(shrink=0.0)
shrink30 = _factory(shrink=30.0)
mir5 = _factory(mir=5)
mir1 = _factory(mir=1)
asym07 = _factory(sim="asym", a=0.7)
asym09 = _factory(sim="asym", a=0.9)
pop03 = _factory(pop_alpha=0.3)
lift = _factory(sim="lift")
lift_w1 = _factory(sim="lift", w=1.0)
asym03 = _factory(sim="asym", a=0.3)
popneg02 = _factory(pop_alpha=-0.2)
w4 = _factory(w=4.0)
shrink3 = _factory(shrink=3.0)
eshrink0 = _factory(shrink=0.0, ishrink=10.0)  # explicit unshrunk, implicit shrink 10
ishrink0 = _factory(shrink=10.0, ishrink=0.0)  # explicit shrink 10, implicit unshrunk
w3_shrink0 = _factory(w=3.0, shrink=0.0)
w4_shrink0 = _factory(w=4.0, shrink=0.0)
