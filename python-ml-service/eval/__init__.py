"""Offline evaluation harness for FilmFuse recommenders.

This package MEASURES recommenders; it contains no ranking logic of its own apart from
the clearly labelled trivial baselines in `eval/baselines.py`. Anything implementing
`app.services.recommender.Recommender` can be evaluated with `python -m eval.run`.
"""
