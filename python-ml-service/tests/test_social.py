"""Friend signal: privacy rules (mirrors core-api's rating visibility) and the maths."""

from uuid import UUID

import numpy as np
import pytest

from app.models.contract import (
    GroupMember,
    GroupRecommendationRequest,
    Strategy,
    UserRecommendationRequest,
)
from app.services import social
from app.services.social import (
    FriendGraph,
    StaticFriendSource,
    build_friend_data,
    friend_signal,
    group_weight_scale,
)
from tests.fixtures import ALL_IDS, fixture_recommender

ME = UUID("11111111-1111-1111-1111-111111111111")
YOU = UUID("22222222-2222-2222-2222-222222222222")
F1 = UUID("33333333-3333-3333-3333-333333333333")
F2 = UUID("44444444-4444-4444-4444-444444444444")
STRANGER = UUID("55555555-5555-5555-5555-555555555555")

PUB, FR, PRIV = ("PUBLIC", "quick_rate"), ("FRIENDS", "quick_rate"), ("PRIVATE", "quick_rate")


def graph(**kw) -> FriendGraph:
    g = FriendGraph(
        closeness={ME: {F1: 1.0, F2: 0.5}, YOU: {F1: 1.0}},
        visibility={F1: {10: FR, 11: FR, 12: PRIV}, F2: {10: PUB, 13: PUB}},
    )
    for k, v in kw.items():
        setattr(g, k, v)
    return g


RATINGS = {F1: [(10, 9), (11, 3), (12, 10)], F2: [(10, 8), (13, 2)]}


def test_private_ratings_never_count_even_through_the_mean() -> None:
    data = build_friend_data(graph(), RATINGS)
    assert 12 not in data.centred[F1]
    assert data.centred[F1] == {10: 3.0, 11: -3.0}  # mean of the visible 9 and 3 only
    flipped = {**RATINGS, F1: [(10, 9), (11, 3), (12, 1)]}  # change the private score
    again = build_friend_data(graph(), flipped)
    ids = [10, 11, 12, 13]
    assert np.array_equal(
        friend_signal(data, data.user_trust(ME), ids),
        friend_signal(again, again.user_trust(ME), ids),
    )


def test_swipes_suspended_and_unknown_visibility_are_excluded() -> None:
    g = graph(visibility={F1: {10: ("FRIENDS", "onboarding_swipe")}, F2: {}})
    data = build_friend_data(g, RATINGS)
    assert F1 not in data.centred and F2 not in data.centred  # swipe / no visibility = private
    data = build_friend_data(graph(suspended={F1}), RATINGS)
    assert F1 not in data.centred and F1 not in data.trust[ME]


def test_blocked_friends_are_dropped() -> None:
    data = build_friend_data(graph(blocked={(ME, F1), (F1, ME)}), RATINGS)
    assert F1 not in data.trust[ME] and F2 in data.trust[ME]


def test_signal_follows_what_friends_liked_and_watched() -> None:
    data = build_friend_data(graph(), RATINGS)
    sig = friend_signal(data, data.user_trust(ME), [10, 11, 13, 99], like=2.0, watch=1.0)
    assert sig[0] > sig[1]  # both friends liked 10; F1 disliked 11
    assert sig[3] == 0.0  # no friend rated it
    # Only watching counts too: an exactly-average rating still gets the watch part.
    zero = friend_signal(data, data.user_trust(ME), [10], like=0.0, watch=1.0)
    assert zero[0] == pytest.approx(1.5 / 2.5)  # trust 1 + 0.5 over (1.5 + shrink 1)


def test_group_trust_unions_friends_excludes_members_and_blocks() -> None:
    g = graph(closeness={ME: {F1: 1.0, F2: 0.5, YOU: 1.0}, YOU: {F1: 1.0, ME: 1.0}})
    data = build_friend_data(g, {**RATINGS, YOU: [], ME: []})
    trust = data.group_trust([ME, YOU])
    assert trust == {F1: pytest.approx(1.0), F2: pytest.approx(0.25)}  # summed / 2 members
    blocked = build_friend_data(graph(blocked={(YOU, F2), (F2, YOU)}), RATINGS)
    assert F2 not in blocked.group_trust([ME, YOU])  # blocked by a member who sees the list


def test_big_groups_weigh_the_friend_signal_less() -> None:
    assert (
        group_weight_scale(2) == group_weight_scale(4) == social.GROUP_WEIGHT / social.LIKE_WEIGHT
    )
    assert group_weight_scale(5) == pytest.approx(group_weight_scale(4) * 4 / 5)
    assert group_weight_scale(6) == group_weight_scale(8) == 0.0  # big groups: members' own only


# ---- inside the model -------------------------------------------------------------------


def _rec(data):
    rec = fixture_recommender()
    rec.friends = StaticFriendSource(data)
    return rec


def _with_friends_liking(movie: int):
    g = FriendGraph(
        closeness={ME: {F1: 1.0}, YOU: {F2: 1.0}},
        visibility={F1: {movie: FR, 13: FR}, F2: {movie: FR, 13: FR}},
    )
    return build_friend_data(g, {F1: [(movie, 10), (13, 4)], F2: [(movie, 10), (13, 4)]})


def _user_scores(rec) -> dict[int, float]:
    from tests.test_content_recommender import NEUTRAL

    req = UserRecommendationRequest(
        user_id=ME, vector=NEUTRAL, candidate_movie_ids=ALL_IDS, k=len(ALL_IDS)
    )
    return {r.movie_id: r for r in rec.recommend_for_user(req).results}


def test_friends_lift_a_movie_and_explain_without_names() -> None:
    plain = _user_scores(_rec(None))
    social_ = _user_scores(_rec(_with_friends_liking(437586)))
    assert social_[437586].score > plain[437586].score
    assert social_[437586].explanation == "Your friends rated it highly"


def test_no_friend_data_means_no_change() -> None:
    assert {k: v.score for k, v in _user_scores(_rec(None)).items()} == {
        k: v.score for k, v in _user_scores(fixture_recommender()).items()
    }


def test_group_merged_profile_gets_the_members_friends() -> None:
    from tests.test_content_recommender import NEUTRAL

    req = GroupRecommendationRequest(
        members=[GroupMember(user_id=ME, vector=NEUTRAL), GroupMember(user_id=YOU, vector=NEUTRAL)],
        candidate_movie_ids=ALL_IDS,
        strategy=Strategy.MEAN,
        k=3,
    )
    plain = [r.movie_id for r in _rec(None).recommend_for_group(req).results]
    boosted = [
        r.movie_id for r in _rec(_with_friends_liking(437586)).recommend_for_group(req).results
    ]
    assert 437586 not in plain and boosted[0] == 437586
