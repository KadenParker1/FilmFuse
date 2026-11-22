"""Overnight item 4: kNN neighbourhood size / implicit weight for the served blend (experiment only).

BlendKNN takes `k` and `implicit_weight` as default arguments bound at import, so `cf.BLEND_K`
overrides don't reach them; these factories register a BlendKNN with the given settings.
Pass the iALS weight with --overrides '{"cf.MF_WEIGHT": 3.0}'.
"""

from __future__ import annotations

import functools

from app.services import collaborative, content_recommender
from app.services.catalog import InMemoryCatalog


def _factory(**kw):
    def make() -> content_recommender.ContentRecommender:
        content_recommender.CF_MODELS["blend"] = functools.partial(collaborative.BlendKNN, **kw)
        return content_recommender.ContentRecommender(InMemoryCatalog([]))

    return make


make_k50 = _factory(k=50)
make_k200 = _factory(k=200)
make_iw1 = _factory(implicit_weight=1.0)
make_iw3 = _factory(implicit_weight=3.0)
