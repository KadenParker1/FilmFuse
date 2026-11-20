"""Round 3: the round-1/2 winners together (run with "cr.CF_MODEL": "blend" for the CF blend).

* GroupDecomp (gd_agg): merged-profile pseudo-member for groups, mad13 settings.
* PoolPriorRecommender (pr_poolprior): quality/popularity prior relative to the candidate pool.
"""

from __future__ import annotations

from app.services.catalog import InMemoryCatalog
from eval.experiments.gd_agg import GroupDecomp
from eval.experiments.pr_poolprior import PoolPriorRecommender

MAD13 = {"merge_lam": 0.7, "prior_g": 1.3, "sim_rcos": 0.3, "sim_params": (1.0, 1.0)}


class Combo(GroupDecomp, PoolPriorRecommender):
    """Group merging on top of the pool-relative prior."""


def _with(rec, settings):
    for k, v in settings.items():
        setattr(rec, k, v)
    return rec


def make_mad13():
    return _with(GroupDecomp(InMemoryCatalog([])), MAD13)


def make_mad13_pool():
    return _with(Combo(InMemoryCatalog([]), p=6.0, half=40.0), MAD13)
