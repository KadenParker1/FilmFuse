from uuid import UUID

import numpy as np
import pytest

from app.models.contract import (
    CalibrationCandidatesRequest,
    GroupMember,
    GroupRecommendationRequest,
    Strategy,
    UserRecommendationRequest,
    UserVector,
)
from app.services.catalog import InMemoryCatalog, InMemoryTasteStore, UserTaste
from app.services.content_recommender import ContentRecommender
from app.services.features import ANIMATION, COMEDY, FAMILY, GENRE_INDEX, HORROR, THRILLER
from app.services.recommender import INTERPRETABLE_DIM
from tests.fixtures import ALL_IDS, FAMILY_IDS, HORROR_IDS, fixture_movies, fixture_recommender

USER_A = UUID("11111111-1111-1111-1111-111111111111")
USER_B = UUID("22222222-2222-2222-2222-222222222222")


def vec(**genres: float) -> UserVector:
    v = [5.0] * INTERPRETABLE_DIM
    for gid, score in genres.items():
        v[GENRE_INDEX[int(gid[1:])]] = score
    return UserVector(interpretable=v)


HORROR_FAN = vec(**{f"g{HORROR}": 10, f"g{THRILLER}": 8, f"g{FAMILY}": 2, f"g{ANIMATION}": 2})
DISNEY_MOM = vec(**{f"g{FAMILY}": 10, f"g{ANIMATION}": 10, f"g{COMEDY}": 7, f"g{HORROR}": 1})
NEUTRAL = UserVector(interpretable=[5.0] * INTERPRETABLE_DIM)


def user_req(vector: UserVector, k: int = 5, **kw) -> UserRecommendationRequest:
    kw.setdefault("candidate_movie_ids", ALL_IDS)
    return UserRecommendationRequest(user_id=USER_A, vector=vector, k=k, **kw)


def ids(resp) -> list[int]:
    return [r.movie_id for r in resp.results]


# ---- personal ---------------------------------------------------------------------------


def test_horror_fan_gets_horror() -> None:
    top = ids(fixture_recommender().recommend_for_user(user_req(HORROR_FAN, k=3)))
    assert top[0] in HORROR_IDS
    assert len(set(top) & HORROR_IDS) >= 2  # the third may be a thriller (Thriller is 8)
    assert not set(top) & FAMILY_IDS


def test_disney_mom_gets_family_and_no_horror() -> None:
    top = ids(fixture_recommender().recommend_for_user(user_req(DISNEY_MOM, k=5)))
    assert top[0] in FAMILY_IDS
    assert len(set(top) & FAMILY_IDS) >= 4
    assert not set(top) & HORROR_IDS


def test_results_respect_pool_exclusions_and_k() -> None:
    pool = [694, 12, 862, 550, 13, 129]
    resp = fixture_recommender().recommend_for_user(
        user_req(DISNEY_MOM, k=3, candidate_movie_ids=pool + [12], exclude_movie_ids=[862])
    )
    got = ids(resp)
    assert len(got) == 3 and len(set(got)) == 3
    assert set(got) <= set(pool) - {862}
    assert all(1.0 <= r.score <= 10.0 for r in resp.results)


def test_cold_start_user_gets_well_known_quality() -> None:
    top = ids(fixture_recommender().recommend_for_user(user_req(NEUTRAL, k=5)))
    assert 99999 not in top  # the obscure low-vote drama
    assert top[0] in {155, 278, 27205, 603}  # big, highly rated films lead


def test_disliked_and_dismissed_movies_sink() -> None:
    store = InMemoryTasteStore({USER_A: UserTaste(disliked={694}, dismissed={138843})})
    top = ids(fixture_recommender(store).recommend_for_user(user_req(HORROR_FAN, k=10)))
    assert 694 not in top[:3] and 138843 not in top[:3]


def test_loved_lookalikes_get_a_boost_and_a_named_reason() -> None:
    store = InMemoryTasteStore({USER_A: UserTaste(loved=[138843], n_signals=20)})  # Conjuring
    rec = fixture_recommender(store)
    resp = rec.recommend_for_user(user_req(NEUTRAL, k=27))
    by_id = {r.movie_id: r for r in resp.results}
    plain = fixture_recommender().recommend_for_user(user_req(NEUTRAL, k=27))
    plain_by_id = {r.movie_id: r for r in plain.results}
    # Hereditary shares "supernatural" + Horror + Thriller with The Conjuring.
    assert by_id[493922].score > plain_by_id[493922].score
    assert by_id[493922].explanation == "Like The Conjuring, which you loved"


def test_explanations_name_a_loved_feature() -> None:
    resp = fixture_recommender().recommend_for_user(user_req(HORROR_FAN, k=3))
    assert all(r.explanation.startswith("Because you love") for r in resp.results)
    assert any("Horror" in r.explanation for r in resp.results)


def test_diversity_mixes_genres() -> None:
    # Two equally loved genres: the top 4 should not be one genre only.
    v = vec(**{f"g{HORROR}": 10, f"g{COMEDY}": 10})
    top = ids(fixture_recommender().recommend_for_user(user_req(v, k=4)))
    assert set(top) & HORROR_IDS and set(top) & {8363, 18785, 13, 862, 277834, 76492}


def test_unknown_movies_keep_pool_order() -> None:
    rec = ContentRecommender(InMemoryCatalog([]))
    pool = [11, 22, 33]
    assert ids(rec.recommend_for_user(user_req(NEUTRAL, k=3, candidate_movie_ids=pool))) == pool


def test_no_pool_uses_well_known_movies() -> None:
    resp = fixture_recommender().recommend_for_user(
        UserRecommendationRequest(user_id=USER_A, vector=NEUTRAL, k=4)
    )
    assert len(resp.results) == 4 and set(ids(resp)) <= set(ALL_IDS)


# ---- group ------------------------------------------------------------------------------


def group_req(strategy: Strategy, members=None, **kw) -> GroupRecommendationRequest:
    members = members or [
        GroupMember(user_id=USER_A, vector=HORROR_FAN),
        GroupMember(user_id=USER_B, vector=DISNEY_MOM),
    ]
    kw.setdefault("candidate_movie_ids", ALL_IDS)
    return GroupRecommendationRequest(members=members, strategy=strategy, k=5, **kw)


def test_every_strategy_returns_scores_for_every_member() -> None:
    rec = fixture_recommender()
    for strategy in Strategy:
        resp = rec.recommend_for_group(group_req(strategy, exclude_movie_ids=[694]))
        assert len(resp.results) == 5, strategy
        assert 694 not in ids(resp)
        for r in resp.results:
            assert set(r.member_scores) == {USER_A, USER_B}
            assert 1.0 <= r.group_score <= 10.0
            assert r.explanation


def test_least_misery_avoids_what_one_member_hates() -> None:
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.LEAST_MISERY))
    for r in resp.results:
        assert min(r.member_scores.values()) >= 3.0
    # Pure horror hurts the Disney mom; pure family hurts the horror fan.
    assert not set(ids(resp)) & {694, 138843}


def test_most_pleasure_can_pick_one_members_favourite() -> None:
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.MOST_PLEASURE))
    assert max(resp.results[0].member_scores.values()) >= 8.0


def test_fairness_serves_both_members() -> None:
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.FAIRNESS))
    best_for = {
        max(r.member_scores, key=r.member_scores.get) for r in resp.results
    }  # whose pick each movie mostly is
    assert best_for == {USER_A, USER_B}


def test_member_without_vector_still_works() -> None:
    members = [GroupMember(user_id=USER_A, vector=HORROR_FAN), GroupMember(user_id=USER_B)]
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.MEAN, members))
    assert len(resp.results) == 5
    assert all(set(r.member_scores) == {USER_A, USER_B} for r in resp.results)


def test_weight_zero_member_is_ignored_by_least_misery() -> None:
    members = [
        GroupMember(user_id=USER_A, vector=HORROR_FAN),
        GroupMember(user_id=USER_B, vector=DISNEY_MOM, weight=0.0),
    ]
    top = ids(fixture_recommender().recommend_for_group(group_req(Strategy.LEAST_MISERY, members)))
    assert top[0] in HORROR_IDS and len(set(top[:3]) & HORROR_IDS) >= 2


def test_group_explanation_names_a_shared_genre() -> None:
    both = vec(**{f"g{FAMILY}": 9, f"g{ANIMATION}": 9, f"g{HORROR}": 2})
    members = [GroupMember(user_id=USER_A, vector=both), GroupMember(user_id=USER_B, vector=both)]
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.MEAN, members))
    assert resp.results[0].explanation.startswith("Everyone's into")


def test_group_explanations_never_name_members() -> None:
    resp = fixture_recommender().recommend_for_group(group_req(Strategy.FAIRNESS))
    for r in resp.results:
        assert str(USER_A) not in r.explanation and str(USER_B) not in r.explanation


# ---- calibration ------------------------------------------------------------------------


def test_calibration_skips_seen_and_spreads_genres() -> None:
    rec = fixture_recommender()
    seen = [278, 238, 155]
    got = rec.calibration_candidates(
        CalibrationCandidatesRequest(user_id=USER_A, seen_movie_ids=seen, k=8)
    ).movie_ids
    assert len(got) == 8 and len(set(got)) == 8
    assert not set(got) & set(seen)
    movies = {m.movie_id: m for m in fixture_movies()}
    first_genres = {movies[m].genre_ids[0] for m in got}
    assert len(first_genres) >= 5


def test_calibration_starts_with_least_seen_genres() -> None:
    rec = fixture_recommender()
    seen = list(HORROR_IDS)
    got = rec.calibration_candidates(
        CalibrationCandidatesRequest(user_id=USER_A, seen_movie_ids=seen, k=3)
    ).movie_ids
    movies = {m.movie_id: m for m in fixture_movies()}
    assert all(HORROR not in movies[m].genre_ids for m in got)


def test_calibration_with_empty_catalog() -> None:
    rec = ContentRecommender(InMemoryCatalog([]))
    req = CalibrationCandidatesRequest(user_id=USER_A, k=5)
    assert rec.calibration_candidates(req).movie_ids == []


# ---- collaborative blend (hybrid-v1) ------------------------------------------------------


def _cf_recommender(user_ratings: dict[int, float]):
    """Ten users who love Hereditary also love Free Solo (a documentary: no genre link)."""
    from app.services.collaborative import ItemKNN, StaticSource

    triples = []
    for u in range(10):
        triples += [(u, 493922, 10), (u, 437586, 10), (u, 8363, 3), (u, 18785, 4)]
    for u in range(10, 20):
        triples += [(u, 493922, 2), (u, 437586, 3), (u, 8363, 9), (u, 18785, 8)]
    store = InMemoryTasteStore(
        {USER_A: UserTaste(ratings=user_ratings, n_signals=len(user_ratings))}
    )
    rec = fixture_recommender(store)
    rec.ratings = StaticSource(ItemKNN(triples, shrink=0.0))
    return rec


def _scores(rec, vector=NEUTRAL) -> dict[int, float]:
    resp = rec.recommend_for_user(user_req(vector, k=len(ALL_IDS)))
    return {r.movie_id: r.score for r in resp.results}


def test_without_cf_evidence_scores_equal_content_only() -> None:
    plain = _scores(fixture_recommender())
    no_ratings = _scores(_cf_recommender({}))
    assert no_ratings == plain


def test_cf_lifts_what_similar_raters_loved() -> None:
    plain = _scores(_cf_recommender({}))
    fan = _scores(_cf_recommender({493922: 10, 8363: 2}))
    assert fan[437586] > plain[437586] + 1  # Free Solo, via Hereditary
    assert fan[18785] < plain[18785]  # The Hangover, liked by the opposite crowd


def test_cf_explanation_names_a_rated_movie() -> None:
    rec = _cf_recommender({493922: 10, 8363: 2})
    resp = rec.recommend_for_user(user_req(NEUTRAL, k=len(ALL_IDS)))
    free_solo = next(r for r in resp.results if r.movie_id == 437586)
    assert free_solo.explanation == "Because you rated Hereditary 10/10"


def test_groups_use_the_hybrid() -> None:
    rec = _cf_recommender({493922: 10, 8363: 2})
    resp = rec.recommend_for_group(group_req(Strategy.MEAN))
    assert len(resp.results) == 5
    assert all(set(r.member_scores) == {USER_A, USER_B} for r in resp.results)


def test_like_minded_group_gets_one_list_for_every_strategy() -> None:
    """Members who rate alike rank fully on the merged profile, so strategies agree."""
    same = {493922: 10.0, 8363: 2.0, 694: 9.0}
    store = InMemoryTasteStore(
        {
            USER_A: UserTaste(ratings=dict(same), n_signals=3),
            USER_B: UserTaste(ratings=dict(same), n_signals=3),
        }
    )
    rec = fixture_recommender(store)
    lists = {
        s: ids(
            rec.recommend_for_group(
                group_req(
                    s,
                    [
                        GroupMember(user_id=USER_A, vector=HORROR_FAN),
                        GroupMember(user_id=USER_B, vector=HORROR_FAN),
                    ],
                )
            )
        )
        for s in Strategy
    }
    assert len({tuple(v) for v in lists.values()}) == 1


def test_group_scores_report_each_members_own_prediction() -> None:
    rec = fixture_recommender()
    group = rec.recommend_for_group(group_req(Strategy.MEAN))
    alone = {
        r.movie_id: r.score
        for r in rec.recommend_for_user(user_req(HORROR_FAN, k=len(ALL_IDS))).results
    }
    for r in group.results:
        if r.movie_id in alone:
            assert r.member_scores[USER_A] == pytest.approx(alone[r.movie_id])


def test_fill_lifts_untouched_genres_once_the_user_has_ratings() -> None:
    """A generous rater's untouched genres shouldn't look disliked; onboarding-only users keep 5."""
    from app.services.content_recommender import _fill_unset

    v = np.full(INTERPRETABLE_DIM, 5.0)
    v[GENRE_INDEX[HORROR]], v[GENRE_INDEX[COMEDY]] = 8.0, 7.0
    filled = _fill_unset(v)
    assert filled[GENRE_INDEX[FAMILY]] == pytest.approx(7.5)  # the user's average
    assert filled[GENRE_INDEX[HORROR]] == 8.0
    assert filled[len(GENRE_INDEX)] == 5.0  # moods untouched: nothing moved there

    rated = InMemoryTasteStore({USER_A: UserTaste(ratings={694: 8.0}, n_signals=1)})
    with_ratings = fixture_recommender(rated).score(
        vec(**{f"g{HORROR}": 8}), rated.taste(USER_A), fixture_movies()
    )
    without = fixture_recommender().score(vec(**{f"g{HORROR}": 8}), UserTaste(), fixture_movies())
    family = next(i for i, m in enumerate(fixture_movies()) if m.movie_id == 12)  # Finding Nemo
    assert with_ratings.affinity[family] > without.affinity[family]
    assert with_ratings.user[GENRE_INDEX[FAMILY]] == 5.0  # explanations see the real profile
