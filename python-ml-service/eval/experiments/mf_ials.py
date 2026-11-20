"""MF experiment: iALS (Hu, Koren & Volinsky 2008) as an extra collaborative component.

NumPy only. Trained on TRAIN triples (user, movie, score 1-10):
  preference p_ui = 1 for every rated movie ("binary") or a graded value ("graded": (r-1)/9;
  "centred": r - user mean, an explicit, confidence-weighted variant), 0 for unrated (binary
  and graded only; "centred" fits observed entries only, weight c on observed, `neg_w` on
  unobserved towards 0);
  confidence c_ui = 1 + alpha * g(r): g = 1 ("const"), r/10 ("lin"), log1p(r) ("log"),
  or max(r - mean_u, 0)/5 + 0.2 ("liked").
Alternating ridge solves; each side via the YtY trick so the cost is O(nnz d^2 + n d^3).

Serving: a user is folded in from their current ratings against the fixed item factors (one
d x d solve), exactly like a live user with new ratings: x_u = (YtY + Y_u^T (C_u - I) Y_u +
lam I)^-1 Y_u^T C_u p_u. Score s_ui = y_i . x_u (approx. preference, ~0..1 for binary).

`MFBlend` wraps hybrid-v5's BlendKNN pieces (explicit "sum" kNN + implicit kNN bonus) and adds
the MF score in one of several roles (see class doc). Factories at the bottom.
"""

from __future__ import annotations

import time

import numpy as np

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog

TIMINGS: dict[str, list[float]] = {"build": [], "fold": []}


class IALS:
    def __init__(
        self,
        triples,
        factors: int = 64,
        reg: float = 0.1,
        alpha: float = 10.0,
        conf: str = "lin",
        pref: str = "binary",
        iters: int = 8,
        min_item_ratings: int = 1,
        neg_w: float = 1.0,
        seed: int = 0,
    ) -> None:
        started = time.perf_counter()
        triples = list(triples)
        counts: dict[int, int] = {}
        for _, i, _ in triples:
            counts[i] = counts.get(i, 0) + 1
        self.items = sorted(i for i, c in counts.items() if c >= min_item_ratings)
        self.index = {m: n for n, m in enumerate(self.items)}
        users = sorted({u for u, _, _ in triples}, key=str)
        uidx = {u: n for n, u in enumerate(users)}
        rows, cols, vals = [], [], []
        for u, i, s in triples:
            j = self.index.get(i)
            if j is not None:
                rows.append(uidx[u])
                cols.append(j)
                vals.append(float(s))
        self.d, self.reg, self.alpha = factors, reg, alpha
        self.conf_mode, self.pref_mode, self.neg_w = conf, pref, neg_w
        n_u, n_i = len(users), len(self.items)
        rows_a = np.array(rows, dtype=np.int64)
        cols_a = np.array(cols, dtype=np.int64)
        r = np.array(vals)
        # per-user mean (for "centred" / "liked")
        sums = np.bincount(rows_a, weights=r, minlength=n_u)
        cnt = np.bincount(rows_a, minlength=n_u)
        means = sums / np.maximum(cnt, 1)
        c, p = self._conf_pref(r, means[rows_a])
        rng = np.random.default_rng(seed)
        self.X = (rng.standard_normal((n_u, factors)) * 0.01).astype(np.float64)
        self.Y = (rng.standard_normal((n_i, factors)) * 0.01).astype(np.float64)
        by_u = _groups(rows_a, cols_a, c, p, n_u)
        by_i = _groups(cols_a, rows_a, c, p, n_i)
        for _ in range(iters):
            self.X = self._solve_side(self.Y, by_u, n_u)
            self.Y = self._solve_side(self.X, by_i, n_i)
        self.YtY = self.neg_w * (self.Y.T @ self.Y)
        self.build_seconds = time.perf_counter() - started
        TIMINGS["build"].append(self.build_seconds)

    # confidence + preference per observed entry
    def _conf_pref(self, r: np.ndarray, mean: np.ndarray):
        g = {
            "const": np.ones_like(r),
            "lin": r / 10.0,
            "log": np.log1p(r),
            "liked": np.maximum(r - mean, 0.0) / 5.0 + 0.2,
        }[self.conf_mode]
        c = 1.0 + self.alpha * g
        if self.pref_mode == "binary":
            p = np.ones_like(r)
        elif self.pref_mode == "graded":
            p = (r - 1.0) / 9.0
        else:  # centred
            p = (r - mean) / 4.5
        return c, p

    def _solve_side(self, F: np.ndarray, groups, n: int) -> np.ndarray:
        d = self.d
        FtF = self.neg_w * (F.T @ F)
        lam = self.reg * np.eye(d)
        out = np.zeros((n, d))
        for e, (idx, c, p) in enumerate(groups):
            if idx is None:
                continue
            Fe = F[idx]
            # A = neg_w FtF + F_e^T (C - neg_w) F_e + lam ; b = F_e^T C p
            A = FtF + (Fe.T * (c - self.neg_w)) @ Fe + lam
            b = Fe.T @ (c * p)
            out[e] = np.linalg.solve(A, b)
        return out

    def fold_in(self, user_ratings: dict[int, float]) -> np.ndarray | None:
        t0 = time.perf_counter()
        pos, rs = [], []
        for m, s in user_ratings.items():
            j = self.index.get(m)
            if j is not None:
                pos.append(j)
                rs.append(float(s))
        if not pos:
            return None
        r = np.array(rs)
        mean = float(np.mean(list(user_ratings.values())))
        c, p = self._conf_pref(r, np.full_like(r, mean))
        Ye = self.Y[pos]
        A = self.YtY + (Ye.T * (c - self.neg_w)) @ Ye + self.reg * np.eye(self.d)
        x = np.linalg.solve(A, Ye.T @ (c * p))
        TIMINGS["fold"].append(time.perf_counter() - t0)
        return x

    def scores(self, user_ratings: dict[int, float], item_ids: list[int]):
        """MF score per item (0 for unknown items), and a known-item mask."""
        out = np.zeros(len(item_ids))
        known = np.zeros(len(item_ids), dtype=bool)
        x = self.fold_in(user_ratings) if user_ratings else None
        if x is None:
            return out, known
        pos = np.array([self.index.get(i, -1) for i in item_ids])
        known = pos >= 0
        out[known] = self.Y[pos[known]] @ x
        return out, known

    def predict(self, user_ratings, item_ids):
        """Stand-alone CFModel (MF only): mean + scale * score; support = constant where known."""
        s, known = self.scores(user_ratings, item_ids)
        mean = float(np.mean(list(user_ratings.values()))) if user_ratings else 0.0
        pred = np.where(known, mean + 5.0 * s, mean)
        return pred, known.astype(float) * 2.0, [None] * len(item_ids)


def _groups(key: np.ndarray, other: np.ndarray, c: np.ndarray, p: np.ndarray, n: int):
    order = np.argsort(key, kind="stable")
    k, o, cs, ps = key[order], other[order], c[order], p[order]
    bounds = np.searchsorted(k, np.arange(n + 1))
    out = []
    for e in range(n):
        a, b = bounds[e], bounds[e + 1]
        out.append((o[a:b], cs[a:b], ps[a:b]) if b > a else (None, None, None))
    return out


class MFBlend:
    """hybrid-v5 BlendKNN + MF.

    role:
      "bonus"   pred = explicit + iw*log1p(implicit evidence) + w * mf   (iw = 2.0 as served)
      "replace" pred = explicit + w * mf                                  (iw = 0)
      "support" bonus, and support = max(explicit support, sup) on MF-known items, with pred = user
                mean + w*mf where explicit has no evidence (MF switches CF on for items the kNN
                can't reach)
    mf is the raw iALS score (centred on the candidates' median when `centre` is True, so only
    the relative MF preference among candidates counts).
    """

    def __init__(
        self,
        triples,
        role: str = "bonus",
        w: float = 2.0,
        iw: float = collaborative.IMPLICIT_WEIGHT,
        sup: float = 0.0,
        centre: bool = False,
        norm: bool = False,
        mf: dict | None = None,
    ) -> None:
        triples = list(triples)
        k = collaborative.BLEND_K
        self.explicit = collaborative.ItemKNN(triples, k=k, mode="sum")
        self.implicit = collaborative.ItemKNN(triples, k=k, mode="implicit") if iw > 0 else None
        self.mf = IALS(triples, **(mf or {}))
        self.items = self.explicit.items
        self.role, self.w, self.iw, self.sup, self.centre = role, w, iw, sup, centre
        self.norm = norm

    def predict(self, user_ratings, item_ids):
        pred, support, top = self.explicit.predict(user_ratings, item_ids)
        if self.implicit is not None:
            _, evidence, _ = self.implicit.predict(user_ratings, item_ids)
            pred = pred + self.iw * np.log1p(evidence)
        s, known = self.mf.scores(user_ratings, item_ids)
        if self.centre and known.any():
            s = np.where(known, s - np.median(s[known]), 0.0)
        if self.norm and known.sum() > 1:
            # z-score among the known candidates: w is then "rating points per std of MF score"
            sd = float(np.std(s[known])) or 1.0
            s = np.where(known, (s - np.mean(s[known])) / sd, 0.0)
        if self.role == "mfonly":
            mean = float(np.mean(list(user_ratings.values()))) if user_ratings else 0.0
            return np.where(known, mean + self.w * s, mean), known * 2.0, top
        if self.role == "support" and user_ratings:
            mean = float(np.mean(list(user_ratings.values())))
            no_ev = support <= 0
            pred = np.where(no_ev, mean, pred)
            support = np.maximum(support, self.sup * known)
        return pred + self.w * s, support, top


def _factory(cls=MFBlend, **kw):
    def make():
        content_recommender.CF_MODELS["mf"] = lambda t: cls(t, **kw)
        content_recommender.CF_MODEL = "mf"
        return content_recommender.ContentRecommender(InMemoryCatalog([]))

    return make


def factory(**kw):
    """For ad-hoc screens: factory(role=..., w=..., mf={...})."""
    return _factory(**kw)


# ---- named variants (filled in as the screen narrows things down) ----------------------------
make_ref = _factory(w=0.0, mf={"factors": 8, "iters": 1})  # = hybrid-v5 (MF weight 0)
MF_BEST = {"reg": 100, "pref": "graded", "alpha": 40}  # 64 factors, conf "lin", 8 iterations
# (a) bonus on top of hybrid-v5's blend: + w * z-scored MF preference among the candidates
make_bn2 = _factory(role="bonus", norm=True, w=2.0, mf=MF_BEST)
# (b) MF replaces the implicit kNN bonus
make_rn3 = _factory(role="replace", iw=0.0, norm=True, w=3.0, mf=MF_BEST)
# (c) bonus + MF also switches CF on: support >= sup on every item MF knows
make_sn3s3 = _factory(role="support", sup=3.0, norm=True, w=3.0, mf=MF_BEST)
make_bn1 = _factory(role="bonus", norm=True, w=1.0, mf=MF_BEST)
# raw (un-normalised) iALS score, ~0..0.8 for graded preference
make_braw10 = _factory(role="bonus", w=10.0, mf=MF_BEST)
make_bn15 = _factory(role="bonus", norm=True, w=1.5, mf=MF_BEST)
make_bn1_d32 = _factory(role="bonus", norm=True, w=1.0, mf=MF_BEST | {"factors": 32})
