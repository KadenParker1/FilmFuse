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
`ML_INTERNAL_KEY` (required unless `ENV=local`, and then at least 32 characters), `ML_DATABASE_URL` (read-only `ml_reader` role;
the model reads movies and taste through it), `ML_MAX_BODY_BYTES` (request body cap, default 5 MiB; larger is a 413), `PORT`.

`/docs`, `/redoc` and `/openapi.json` exist only when `ENV` is not `production`. `/health` is
always public; everything under `/internal` needs the key.

## Test

```bash
.venv/bin/pytest -q && .venv/bin/ruff check . && .venv/bin/ruff format --check .
```

## The model: hybrid-v7 (content + kNN + iALS + friends)

`app/services/content_recommender.py` is the live model. It starts from content-v1 (Phase 0
of `docs/recommender-directions.md`: genre and mood matching) and blends in item-kNN
collaborative filtering (`collaborative.py`) wherever co-ratings give evidence. Without that
evidence (new users, movies few people rated) it is exactly content-v1.

| Piece | File | What it does |
|---|---|---|
| Movie features | `features.py` | Genre one-hot plus 8 mood strengths derived from genres and TMDB keywords (`MOOD_RULES`, plain data, easy to tune). |
| Catalog and taste | `catalog.py` | `app.movies` held in memory (warmed at startup, refreshed every 6h in the background, unknown ids fetched on demand); each user's loved, disliked and dismissed movies. Read through `ML_DATABASE_URL` (read-only `ml_reader`). |
| User vector | `content_recommender.py` | Each of the 27 dims starts at the stated preference (5 if unset) and is pulled towards the user's scores for movies with that feature. Favourites count as a 10 and least favourites as a 1 (weight 3 each), dismissals as a mild 3, and older ratings count less. |
| Predicted score | `content_recommender.py` | Taste affinity (0.7 × weighted mean of the user's dims + 0.3 × their lowest genre dim, so one hated genre sinks a movie), plus similarity to loved and disliked movies, plus a quality/popularity prior that weighs more for new users. |
| Collaborative part | `collaborative.py` | Item-kNN: movies are neighbours when people rate them alike (mean-centred ratings, adjusted cosine shrunk by co-rater count, top 50 per movie). Built from all of `app.ratings` in the background, rebuilt hourly; a user's own ratings are read fresh on every request. Blended as `taste = (1-β)·content + β·kNN`, with `β = 0.9·support/(support+2)`. EASE is in the same file for offline comparison (it lost: NDCG@10 0.08 vs 0.09). |
| Matrix factorisation | `collaborative.py` (`IALS`) | iALS (64 factors, graded preference, linear confidence), NumPy only; the user is folded in from their current ratings per request; its z-scored score is a bonus inside the blend (MF_WEIGHT 3.0 since hybrid-v7; 1.0 in v6). |
| Friend signal | `social.py` | What friends thought, from ratings they share (PUBLIC/FRIENDS; never PRIVATE, blocks and suspended accounts respected); the group's merged profile gets the members' friends (not for groups of 6+). |
| Lists | `content_recommender.py` | "For you" uses a light genre-diversity re-rank. Groups go through `group_strategies.py`: mean, least misery, most pleasure, closeness-weighted (the group's average closeness blends mean and least misery) and a GFAR-style fairness list. |
| Explanations | `content_recommender.py` | Built from the score's largest actual contribution, for example "Because you love Horror", "Like Coco, which you loved", "Because you rated Alien 9/10" or "Highly rated and popular". Group lines never single out a member. |
| Calibration | `content_recommender.py` | Well-known movies, round-robin across genres, starting with the genres the user has rated least. |

Without `ML_DATABASE_URL` the model has no movie metadata. It then keeps core-api's pool order
and never errors. Every weight is a named constant at the top of its file: judge a change with
`python -m eval.run` (below) and `tools/personas/sessions.py` (live).

Locally, `ml_reader` has no login until you give it one (never commit the password):

```bash
docker exec <supabase_db container> psql -U postgres -c "alter role ml_reader with login password '<local-only>'"
ENV=local ML_DATABASE_URL=postgresql://ml_reader:<local-only>@127.0.0.1:55322/postgres \
  .venv/bin/uvicorn app.main:app --port 8001
```

To try a different model, implement the `Recommender` protocol in `app/services/recommender.py`
and return it from `get_recommender()`. You don't need to touch the routes or the contract.

## Contract (frozen; see `app/models/contract.py`)

JSON is camelCase. Scores are integers from 1 to 10, `k` is between 1 and 100, user ids are
UUIDs and movie ids are TMDB ints.

| Endpoint | Request | Response |
|---|---|---|
| `POST /internal/user-vectors` | `userId, previousVector?, genrePrefs[{genreId,score}], moodPrefs[{moodTag,score}], favorites[{movieId,rank}], leastFavorites?[{movieId,rank}], ratings[{movieId,score,source,createdAt}], unseen[], dismissals[]` | `{vector:{interpretable[], latent?}, dim, modelVersion}` |
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
- `leastFavorites` (added 2026-11-08, additive and optional, defaults to `[]`): the user's 0 to 5
  least favourite movies, ranked (rank 1 = most disliked). A movie is never in both `favorites`
  and `leastFavorites`. The placeholder accepts the field and ignores it.
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
