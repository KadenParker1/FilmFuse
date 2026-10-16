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

Env vars (see `.env.example`): `ENV`, `ML_INTERNAL_KEY` (required unless `ENV=local`),
`ML_DATABASE_URL` (read-only `ml_reader` role, optional for now), `PORT`.

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
