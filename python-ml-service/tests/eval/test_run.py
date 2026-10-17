import json
from pathlib import Path

from app.models.contract import (
    UserRecommendation,
    UserRecommendationRequest,
    UserRecommendationResponse,
)
from app.services.recommender import PlaceholderRecommender
from eval.data import Dataset
from eval.harness import EvalConfig, EvalTrainingData, build_context, evaluate, load_recommender
from eval.run import main


def test_end_to_end_with_placeholder(tiny_data_dir: Path, tmp_path: Path) -> None:
    out = tmp_path / "reports" / "r.md"
    argv = [
        "--recommender",
        "app.services.recommender:get_recommender",
        "--k",
        "5",
        "--users",
        "8",
        "--groups",
        "4",
        "--seed",
        "42",
        "--data-dir",
        str(tiny_data_dir),
        "--out",
        str(out),
    ]
    assert main(argv) == 0
    report = out.read_text()
    assert "## Individual recommendations" in report
    assert "baseline:random" in report and "baseline:popularity" in report

    data = json.loads(out.with_suffix(".json").read_text())
    names = [r["name"] for r in data["recommenders"]]
    assert names == [
        "app.services.recommender:get_recommender",
        "baseline:random",
        "baseline:popularity",
    ]
    for r in data["recommenders"]:
        assert r["contract_violations"] == {}
        assert set(r["individual"]) >= {"precision@5", "recall@5", "ndcg@5", "hit_rate@5"}
        assert set(r["group"]) == {"random", "similar", "dissimilar"}
        assert set(r["group"]["random"]) == {
            "mean",
            "least_misery",
            "most_pleasure",
            "closeness_weighted",
            "fairness",
        }
        for strategies in r["group"].values():
            for m in strategies.values():
                assert m["n_groups"] == 4
                assert 0 <= m["mean_satisfaction@5"] <= 1
                assert m["min_satisfaction@5"] <= m["mean_satisfaction@5"]

    # same seed -> same metrics
    out2 = tmp_path / "r2.md"
    assert main(argv[:-1] + [str(out2)]) == 0
    data2 = json.loads(out2.with_suffix(".json").read_text())
    strip = [{k: v for k, v in r.items() if k != "timing"} for r in data["recommenders"]]
    strip2 = [{k: v for k, v in r.items() if k != "timing"} for r in data2["recommenders"]]
    assert strip == strip2


def test_load_recommender_accepts_factory_class_or_instance() -> None:
    assert isinstance(
        load_recommender("app.services.recommender:get_recommender"), PlaceholderRecommender
    )
    assert isinstance(
        load_recommender("app.services.recommender:PlaceholderRecommender"),
        PlaceholderRecommender,
    )


class _Cheater(PlaceholderRecommender):
    """Returns already-rated movies and duplicates; the harness must count, not score them."""

    def recommend_for_user(self, req: UserRecommendationRequest) -> UserRecommendationResponse:
        ids = req.exclude_movie_ids[:2] + [req.candidate_movie_ids[0]] * 2
        return UserRecommendationResponse(
            results=[UserRecommendation(movie_id=m, score=5, explanation="x") for m in ids]
        )


class _Prepared(PlaceholderRecommender):
    def prepare_for_eval(self, data: EvalTrainingData) -> None:
        self.seen_users = len(data.train)
        self.seen_movies = len(data.movies)


def test_violations_counted_and_prepare_hook(tiny_dataset: Dataset) -> None:
    ctx = build_context(tiny_dataset, EvalConfig(k=5, n_users=4, n_groups=0))
    res = evaluate(_Cheater(), ctx, "cheater")
    assert res["contract_violations"]["excluded_result"] == 8
    assert res["contract_violations"]["duplicate_result"] == 4
    assert res["group"] == {}

    prepared = _Prepared()
    evaluate(prepared, ctx, "prepared")
    assert prepared.seen_users == len(ctx.split.train)
    assert prepared.seen_movies == 40
