# Test personas and fake social networks (local stack)

44 hand-written test personas (Disney mom, film bro, horror fanatic, cross-genre combos, edge
cases such as a G-capped parent or a never-onboarded account), wired into fake social networks
(family, film school, office, roommates, horror club, book club, …) and seeded into the **local**
stack through the real APIs. Use them to click around the app as realistic users and to run group
sessions for the recommender.

- **[ROSTER.md](ROSTER.md)**: who's who, split into single-lane archetypes, cross-genre combos and
  behaviour & edge cases, with what each persona tests. GENERATED, don't edit it.
- **[accounts.csv](accounts.csv)**: one row per login (email, password, taste, favourites, friends,
  groups). It opens in Excel or Numbers.
- **[networks.csv](networks.csv)** / **[groups.csv](groups.csv)**: every edge
  (friend | close | pending | block) and every saved group with its intent
  (easy | hard | big | pair).

The passwords are **local-only throwaway credentials** for `supabase start`, like the table in
`tools/e2e/README.md`. They exist nowhere else.

> **None of this is the recommendation algorithm.** `generate.py` invents *synthetic* rating
> histories so the accounts look lived-in. `sessions.py` only *measures* what the platform
> recommends against each member's stated preferences. The algorithm belongs to the project
> owner (`python-ml-service/`).

## Files

| File | What |
|---|---|
| `SCHEMA.md` | the persona file contract |
| `personas.json` | the personas (hand-written) |
| `tmdb-overrides.json` | `"Title (Year)": tmdbId` for titles MovieLens doesn't have (post-2018) |
| `resolve.py` | validates `personas.json` and resolves every title to a TMDB id, writing `personas.resolved.json` (committed) |
| `generate.py` | writes deterministic synthetic ratings to `out/ratings.generated.json` (gitignored) |
| `networks.json` / `networks.py` | the fake social networks (clusters, extra edges, isolates, groups), plus `validate` / `summary` |
| `export_csv.py` | writes `accounts.csv`, `networks.csv`, `groups.csv` and `ROSTER.md` (no stack needed) |
| `seed.py` | seeds accounts, preferences, ratings, friends, blocks and groups through Supabase Auth + core-api. It is idempotent |
| `sessions.py` | creates a group session per group × strategy and writes `out/session-report.md` (+ `.json`) |
| `out/` | gitignored: generated ratings, `seed-state.json` (persona → user id, group → id), session reports |

Every script uses only the standard library, runs as `python3 -I tools/personas/<script>.py` from
the repo root, and has `--help`.

## Runbook

### 1. Start the local stack

This is the same as `tools/e2e/README.md`, plus the two seeding notes below.

```bash
supabase start                                   # ports 553xx (API 55321, Studio 55323, Mailpit 55324)

cd python-ml-service && ENV=local .venv/bin/uvicorn app.main:app --port 8001 &

cd core-api && ./mvnw -q -DskipTests package
eval "$(supabase status -o env | grep -E '^(SERVICE_ROLE_KEY|ANON_KEY)=')"
SUPABASE_SERVICE_ROLE_KEY="$SERVICE_ROLE_KEY" ML_SERVICE_URL=http://127.0.0.1:8001 \
  ML_INTERNAL_KEY=local-dev-internal-key SERVER_PORT=8090 \
  FILMFUSE_RATE_LIMIT_ENABLED=false TMDB_ACCESS_TOKEN=... \
  java -jar target/core-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local &
```

- **`FILMFUSE_RATE_LIMIT_ENABLED=false`** turns off core-api's rate limits (120 requests/min per
  user, 600/min per IP, `POST /sessions` 30/hour), so a full seed of about 3,000 ratings runs in
  minutes. With limits on, `seed.py` still works: it honours `429 Retry-After`, only more slowly.
  `prod` refuses this setting.
- **The movie catalog must know the persona movies.** Without `TMDB_ACCESS_TOKEN`, core-api serves
  a fixture catalog of about 49 movies, so most favourites and ratings would 404 and be skipped.
  Either start core-api with a TMDB token (unknown movies are then fetched on demand), or
  bulk-fill the catalog first:
  `python3 tools/catalog/movielens_tmdb_ids.py`, then run the catalog seeder with
  `--filmfuse.catalog.seed.id-file=../tools/catalog/out/movielens-tmdb-ids.txt` (see the
  Commands block in `CLAUDE.md`). Check with `seed.py --preflight`.
- **Supabase Auth also rate-limits** sign-ups and sign-ins (`[auth.rate_limit] sign_in_sign_ups =
  30` per 5 minutes per IP in `supabase/config.toml`). Seeding 44 new accounts hits that limit
  once. `seed.py` backs off and retries (Auth sends no `Retry-After`), so the first run pauses for
  a few minutes. Re-runs sign in once per persona.

### 2. Build the data (no stack needed)

```bash
python3 -I tools/personas/resolve.py            # personas.json -> personas.resolved.json
python3 -I tools/personas/networks.py validate  # networks.json against the persona keys
python3 -I tools/personas/networks.py summary   # clusters, edge counts, groups
python3 -I tools/personas/generate.py           # -> out/ratings.generated.json (needs MovieLens:
                                                #    cd python-ml-service && .venv/bin/python -m eval.download_movielens)
python3 -I tools/personas/export_csv.py         # -> accounts.csv, networks.csv, groups.csv, ROSTER.md
```

### 3. Seed

```bash
eval "$(supabase status -o env | grep '^ANON_KEY=')"
ANON_KEY="$ANON_KEY" python3 -I tools/personas/seed.py --preflight   # does the catalog know the movies?
python3 -I tools/personas/seed.py --dry-run                          # the plan, no HTTP
ANON_KEY="$ANON_KEY" python3 -I tools/personas/seed.py               # everything (idempotent)
ANON_KEY="$ANON_KEY" python3 -I tools/personas/seed.py --only personas --keys disney-mom,film-bro
ANON_KEY="$ANON_KEY" python3 -I tools/personas/seed.py --only network
```

For each persona, `seed.py` does the following:

1. Signs up, or signs in if the account exists.
2. Calls `GET /me`, then `PATCH /me` with the username, display name and bio.
3. `PUT`s the genre, mood and favourite preferences.
4. `PUT`s the comfort settings.
5. Completes onboarding, unless the persona has `onboarded: false`.
6. `PUT /ratings/{id}` for the anchors plus the generated ratings. About 20% are PUBLIC, some with
   a short in-character note so the feed has content, and about 10% are PRIVATE. Unchanged
   ratings are skipped.

After the personas come the network steps:

- friend requests and accepts
- close-friend flags
- pending requests, which are sent and never answered
- blocks (`POST /blocks/{id}`)
- groups, with every invitee accepting

On a re-run, accounts sign in and existing friendships, requests, blocks and groups are detected
before anything is written. 409s are reported with their reason.

When a movie isn't in the catalog (404), `seed.py` skips it and reports it per persona. If fewer
than 3 of a persona's favourites exist, it fills the rest from `/movies/search` and then
`/movies/popular`, and prints `!!! FALLBACK FAVOURITES`.

### 4. Group sessions report

```bash
ANON_KEY="$ANON_KEY" python3 -I tools/personas/sessions.py                     # MEAN, every group
ANON_KEY="$ANON_KEY" python3 -I tools/personas/sessions.py --strategies all    # 5 strategies
ANON_KEY="$ANON_KEY" python3 -I tools/personas/sessions.py --groups "Chaos Movie Night" --end
```

This writes `out/session-report.md` and `.json`. For each result it gives:

- each member's stated-genre affinity
- the min, mean and spread across members
- each member's share of results they'd likely enjoy (affinity ≥ 7)
- **comfort violations**: a certification above a member's cap, or unrated when they block
  unrated. These are flagged as BUGs and the script exits with code 2.

The report is a diagnostic based on stated preferences, not ground truth. Use it to compare the
placeholder with the owner's model on the same groups.

`POST /sessions` is limited to 30 per hour per user. When a creator hits a 429, their remaining
sessions are recorded as rate-limited; `--wait-on-limit` waits instead. Sessions stay OPEN, so
members can vote in the app. Pass `--end` to close them.

## Log in as a persona

Open the web client at <http://localhost:8081> (`cd client && npx expo start`, then press `w`) and
sign in with any row of `accounts.csv`: email `<key>@filmfuse.test`, password
`ff-persona-local-1`. For example, `strict-parent@filmfuse.test` shows the G cap, and
`never-onboarded@filmfuse.test` lands in onboarding. See [ROSTER.md](ROSTER.md) for who's who.

## Reset

```bash
supabase db reset          # wipes every local account and app table (core-api re-migrates on start)
rm -f tools/personas/out/seed-state.json
```

Then seed again. `seed.py` also recovers on its own: if `seed-state.json` is stale, a failed
sign-in falls back to sign-up.

## Tests

```bash
python-ml-service/.venv/bin/pytest -q tools/personas
python-ml-service/.venv/bin/ruff check tools/personas && python-ml-service/.venv/bin/ruff format --check tools/personas
```

The seeder and session tests run against an in-memory fake of Supabase Auth and core-api
(`tests/personas_testlib.py`). MovieLens-dependent tests use a tiny fake dataset, so CI doesn't
need the download. A test also fails when the committed `ROSTER.md` or CSVs are stale.
