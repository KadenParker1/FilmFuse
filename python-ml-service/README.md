# FilmFuse ML service

FastAPI service that builds taste vectors and ranks movies for core-api. It is
reachable only over Railway private networking. Every `/internal/*` call must
send the `X-Internal-Key` header.

## Run

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
ENV=local .venv/bin/uvicorn app.main:app --reload --port 8001   # dev key: local-dev-internal-key
```

Env vars (see `.env.example`): `ENV` (`local` | `test` | `production`; unset = `production`),
`ML_INTERNAL_KEY` (required unless `ENV=local`), `ML_DATABASE_URL` (read-only `ml_reader` role,
optional for now), `ML_MAX_BODY_BYTES` (request body cap, default 5 MiB; larger is a 413), `PORT`.

`/docs`, `/redoc` and `/openapi.json` exist only when `ENV` is not `production`. `/health` is
always public; everything under `/internal` needs the key.

## Test

```bash
.venv/bin/pytest -q && .venv/bin/ruff check . && .venv/bin/ruff format --check .
```

## Where the algorithm goes

`app/services/recommender.py` defines the `Recommender` protocol. It has four methods:
`build_user_vector`, `recommend_for_group`, `recommend_for_user` and `calibration_candidates`.
`PlaceholderRecommender` is an intentionally dumb stand-in, and every part of it is marked
`# PLACEHOLDER — replace with your implementation`. To plug in your own code, implement the
protocol and change the one line in `get_recommender()`. You don't need to touch the routes
or the contract.

## Contract (frozen; see `app/models/contract.py`)

JSON is camelCase. Scores are integers from 1 to 10, `k` is between 1 and 100, user ids are
UUIDs and movie ids are TMDB ints.

| Endpoint | Request | Response |
|---|---|---|
| `POST /internal/user-vectors` | `userId, previousVector?, genrePrefs[{genreId,score}], moodPrefs[{moodTag,score}], favorites[{movieId,rank}], ratings[{movieId,score,source,createdAt}], unseen[], dismissals[]` | `{vector:{interpretable[], latent?}, dim, modelVersion}` |
| `POST /internal/group-recommendations` | `members[{userId,vector?,weight}], closeness[n][n], filters, excludeMovieIds, candidateMovieIds?, strategy, k` | `{results:[{movieId, groupScore, memberScores{userId:score}, explanation}]}` |
| `POST /internal/user-recommendations` | `userId, vector, filters, excludeMovieIds, candidateMovieIds?, k` | `{results:[{movieId, score, explanation}]}` |
| `POST /internal/calibration-candidates` | `userId, seenMovieIds, k` | `{movieIds:[]}` |

- `strategy` is one of `mean`, `least_misery`, `most_pleasure`, `closeness_weighted` or `fairness`.
- `filters` has the optional fields `maxRuntime`, `minYear`, `maxContentRating`,
  `streamingServices`, `genreIds`, `maxCertification` (`G`, `PG`, `PG-13`, `R`, `NC-17`),
  `contentLimits` (`{violence|sexual|language|drugs|frightening: none|mild|moderate|strong}`)
  and `allowUnrated` (default `false`).
- Hard constraints: filters (certification and content limits included) must never be
  violated. core-api re-checks every result and drops violations.
- `candidateMovieIds` is an optional, pre-filtered eligible pool from core-api. When it is
  present, rank only within it. The placeholder keeps the given order, minus
  `excludeMovieIds`, cut to `k`.
- A group member's `vector` may be `null` (no vector yet). That member is still listed in
  `memberScores`.
- The interpretable vector has one dimension per genre in `GENRE_IDS`, followed by one per mood
  in `MOOD_TAGS`, in that fixed order.

## Evaluating your recommender

`eval/` is an offline measuring stick. It scores **any** object that implements the
`Recommender` protocol on MovieLens `ml-latest-small`, driving it only through the frozen
contract models, and compares it with two trivial baselines. It contains no ranking logic
apart from those baselines (`eval/baselines.py`: random, and global train popularity).

```bash
.venv/bin/python -m eval.download_movielens        # once, ~1 MB into eval/data/ (gitignored)
.venv/bin/python -m eval.run \
  --recommender app.services.recommender:get_recommender \
  --k 10 --users 200 --groups 100 --seed 42        # ~10 s with the placeholder
```

The report goes to `eval/reports/<timestamp>.md` (or `--out`), with every number also in a
`.json` next to it. `--recommender` takes `module:attr`, where attr is an instance, a class
or a zero-argument factory. Other flags: `--relevance-threshold` (default 8),
`--test-fraction`, `--min-ratings`, `--min-group-size`/`--max-group-size`, `--closeness`,
`--strategies`, `--data-dir`, `--no-baselines`.

**How the data is built.**
- MovieLens ids are mapped to TMDB ids through `links.csv`. Ratings are mapped from 0.5–5 to 1–10
  with `round(stars × 2)`.
- Genres are mapped to `GENRE_IDS`. The full table is in `eval/data.py`. Children maps to
  Family, Musical to Music and Film-Noir to Crime. IMAX and "(no genres listed)" are ignored.
- Split: for each user with 20 or more ratings, the last 20% of their ratings by time are the
  test set. The split is deterministic.
- Each user's train ratings become a `UserVectorRequest` (see `eval/builders.py`):
  - every train rating, with source `quick_rate`
  - `genrePrefs`, which are stated prefs: the rounded mean train rating per genre
  - `favorites`: up to 5 train movies rated 9 or higher
  - no moods
- The harness then calls `build_user_vector`, followed by `recommend_for_user`. Candidates are
  every movie the user hasn't rated in train, in a seeded random order, so that echoing the
  pool order scores like random. Train items are also sent as `excludeMovieIds`.
- Groups have 2–6 members, are seeded, and come in three kinds: `random`, `similar` (greedy
  highest train-rating cosine) and `dissimilar` (greedy lowest). Each member's vector comes
  from `build_user_vector`. Off-diagonal closeness is 0.5. Each group is run once per
  `Strategy`, and its candidates exclude anything any member rated in train.
- Offline there is no database, so the request carries only ids. If your recommender needs
  movie metadata or other users' interactions, give it an optional
  `prepare_for_eval(data: EvalTrainingData)` method (`eval/harness.py`). The harness calls it
  once with the catalog (title, year, TMDB genres) and all train ratings. It never receives
  test data.

**Metrics.** Relevant means a test rating ≥ 8/10. Only users with at least one relevant test
item are evaluated.

| Metric | Meaning | Better |
|---|---|---|
| precision@k | share of the top k that are relevant | higher |
| recall@k | share of the user's relevant test items that appear in the top k | higher |
| NDCG@k | like recall, but rank-aware: a hit at rank 1 counts more than one at rank k | higher |
| hit-rate@k | share of users with at least one relevant item in the top k | higher |
| catalog coverage | share of the catalog recommended to anyone | higher = more diverse |
| mean popularity rank | mean train-popularity rank of recommended items (1 = most rated) | lower = more popularity-biased; random ≈ catalog/2 |
| group mean satisfaction | each member's NDCG@k of the shared list on their *own* test items, averaged | higher |
| group min satisfaction | the least happy member (least misery), averaged over groups | higher |
| satisfaction std | spread of member satisfaction within a group (fairness) | lower = fairer |
| % all-members-hit | groups where every member got at least one relevant item in the top k | higher |

The report also lists **contract violations**, which are counted and never scored: duplicate
results, results that were excluded or not among the candidates, more than k results, and
`memberScores` that don't match the members.

**What to expect.** Absolute numbers on MovieLens are low, because the test set is a handful of
future ratings drawn from about 9,700 movies. Compare against the baselines rather than reading
the numbers on their own. The `PlaceholderRecommender` echoes the shuffled candidate order, so
it scores about the same as `baseline:random`. Popularity is a strong baseline on MovieLens. A
real recommender should beat it on NDCG and hit rate without falling to its catalog coverage
(about 1%).
