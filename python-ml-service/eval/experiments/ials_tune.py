"""Overnight item 3: iALS hyperparameter screen on ml-32m (experiment only).

IALS's factors/reg/alpha are default arguments bound at import, so `cf.MF_*` overrides don't
reach them. Each factory here swaps `collaborative.IALS` for a version with the given settings
(per process; every experiment run is its own process). MF_WEIGHT is a runtime constant, so pass
it with --overrides '{"cf.MF_WEIGHT": ...}'.

    python -m eval.experiment run --quick --name ials-f32-s1 --data eval/data/ml-32m-u3000-s1 \\
        --recommender eval.experiments.ials_tune:make_f32
"""

from __future__ import annotations

import functools

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog

_ORIGINAL = collaborative.IALS


def _factory(**kw):
    def make() -> content_recommender.ContentRecommender:
        collaborative.IALS = functools.partial(_ORIGINAL, **kw)
        return content_recommender.ContentRecommender(InMemoryCatalog([]))

    return make


make_f32 = _factory(factors=32)
make_f96 = _factory(factors=96)
make_r30 = _factory(reg=30.0)
make_r300 = _factory(reg=300.0)
make_a10 = _factory(alpha=10.0)
make_a100 = _factory(alpha=100.0)
