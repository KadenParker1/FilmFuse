# FilmFuse

Social group movie recommender. Users onboard by scoring their taste, rate movies 1–10 after watching, add friends, form groups (saved or ad-hoc "Tonight" sessions), and get recommendations that fuse every member's interest vector into a group matrix. Also gives personal "For you" recommendations. Ships as a native app (iOS/Android) **and** a web app from one codebase.

Full roadmap, API design and data model: `~/.claude/plans/humble-waddling-cray.md` (copy into `docs/` if it needs to live in the repo).

## Ownership — read first
- **The recommendation algorithm belongs to the project owner.** Interest-vector construction, the group matrix, aggregation strategies (mean / least-misery / closeness-weighted / …), ranking, explanations and adaptive calibration live in `python-ml-service/` and are written by the owner. Claude builds plumbing, contracts, tests and evaluation harnesses around it, and may write clearly-marked **placeholder** implementations — but does not design or write the real algorithm unless explicitly asked.
- Don't change the core-api ↔ ML-service contract without flagging it; the owner codes against it.

## Architecture (target)
| Part | Stack | Hosting |
|---|---|---|
| `client/` | Expo universal (React Native + TS), Expo Router tabs, TanStack Query 5, NativeWind, supabase-js | Web: static export on Vercel. Native: EAS Build, Expo Go in dev |
| `core-api/` | Spring Boot 4.1, Java 21, Maven, Flyway, Spring Security OAuth2 resource server | Railway (Docker) |
| `python-ml-service/` | FastAPI, Pydantic | Railway (Docker), private networking |
| Data/Auth | Supabase Postgres (+ pgvector), Supabase Auth, Supabase Storage (`profile-photos`), Supabase Realtime (session voting) | Supabase |
| Email | Resend via SMTP (`smtp.resend.com:2587`) | — |
| Movies | TMDB API, cached into `movies` table (attribution required in UI) | — |

Stack and conventions mirror the sibling project `~/IdeaProjects/bellavera/bellavera` — copy patterns from there (JWKS security config, `@CurrentUser`, profiles, Dockerfile, `railway.json`, `EmailGateway`, CI, `JwtTestSupport`).

### Key rules
- **Auth:** Supabase Auth only (email, Google, Sign in with Apple). core-api verifies Supabase JWTs against JWKS (ES256). Profiles are auto-provisioned on first `/me` call; `profiles.id` = Supabase user id.
- **core-api owns all writes.** App tables have no RLS, so schema `app` must not be exposed via Supabase's Data API. The ML service connects with a **read-only** role (`ml_reader`) for training/candidate retrieval and returns results over HTTP.
- **Flyway owns schema `app`.** Never edit an applied migration; add a new `V<n>__*.sql`.
- **One scale:** every score (genre/mood prefs, calibration, ratings) is an integer 1–10. `ratings` is the single source of truth for movie scores; a "review" is a rating with an optional note and visibility ≠ private.
- **Taste vectors** are rebuilt asynchronously: any taste signal sets `user_interest_vectors.dirty`; `VectorRefreshScheduler` calls the ML service. Taste-editor sliders edit *stated* prefs, never the vector directly.
- **Social graph** lives in Postgres (`friendships` canonical pair + directional `friend_closeness`). All graph access goes through `SocialGraphService` so it could move to Neo4j later.
- **Privacy default:** friends-only. Respect blocks in every read (search, feed, profiles, suggestions).
- Single Railway replica for core-api (scheduler isn't distributed-locked).

## Conventions
- core-api packages by feature: `com.pm.filmfuse.{user,social,group,movie,reco,email,common}`, each with `controller/ service/ repository/ model/ dto/ mapper/`.
- DTO in/out of controllers, static mapper classes (`UserMapper.toDTO/toModel`), Jakarta validation on request DTOs, errors via the common `@ControllerAdvice` returning `{message, fieldErrors?}`. Custom exceptions extend `RuntimeException` and get a handler.
- REST under `/api/v1`, cursor pagination (`?cursor=&limit=`).
- `.http` files for manual testing in `api-requests/<module>/`.
- Client: keep `client/lib`, `client/api` (query hooks) and `client/types` free of platform-specific imports; platform splits via `*.web.tsx` / `*.native.tsx`. Design tokens in `client/theme/tokens.ts` ("Marquee" palette: bg `#0E0B14`, surface `#1A1523`, text `#F4EFE6`, gold `#F5B942`, velvet `#C7354A`).
- `client/types/api.ts` mirrors core-api DTOs by hand; CI checks for drift.

## Commands
```bash
# Local infra (Postgres, Auth, Storage, Mailpit at :54324)
supabase start

# core-api (once: cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties)
cd core-api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd core-api && ./mvnw test            # Testcontainers Postgres; Docker must be running

# ML service (once: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-dev.txt)
cd python-ml-service && ENV=local .venv/bin/uvicorn app.main:app --reload --port 8001   # ENV=local supplies a dev X-Internal-Key
cd python-ml-service && .venv/bin/pytest && .venv/bin/ruff check .

# client (once: cp .env.example .env.local and fill in from `supabase status`)
cd client && npx expo start           # press i / a / w for iOS, Android, web
cd client && npm run typecheck && npm run lint && npm test

# DTO drift between core-api and client/types/api.ts
python3 tools/check_api_type_drift.py
cd client && npx expo export -p web   # what Vercel builds
```

## Environment variables
- core-api: `DATABASE_URL` (session pooler, `currentSchema=app`), `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `SUPABASE_URL`, `SUPABASE_JWKS_URI`, `SUPABASE_ISSUER`, `SUPABASE_SERVICE_ROLE_KEY` (storage signed URLs, account deletion, realtime broadcast; blank → mock storage / auth-admin gateways, required under `prod`), `TMDB_ACCESS_TOKEN` (TMDB v4 read access token; blank → offline fixture catalog `catalog/fixture-movies.json`), `ML_SERVICE_URL`, `ML_INTERNAL_KEY`, `APP_CORS_ALLOWED_ORIGINS`, `PORT`, `SPRING_MAIL_*`, `FILMFUSE_EMAIL_*`.
- python-ml-service: `ML_DATABASE_URL` (read-only role), `ML_INTERNAL_KEY`.
- client: `EXPO_PUBLIC_SUPABASE_URL`, `EXPO_PUBLIC_SUPABASE_ANON_KEY`, `EXPO_PUBLIC_API_BASE_URL` (baked in at build time).
- Never commit secrets; `.env.example` files list names only.

## Current state (2026-10-16) — Phase 0 complete (branch `phase-0-foundation`)
- `core-api/`: security (Supabase JWKS, `@CurrentUser` auto-provisioning, suspended-account check), common error handling, email gateway (mock/smtp), Flyway `V1__profiles`, `V2__ml_reader_role`. Endpoints: `GET/PATCH /api/v1/me`, `GET /api/v1/usernames/{username}/available`. Swagger at `/swagger-ui.html` off-prod only.
  - Phase 2 social graph (branch `phase-2-social`): Flyway `V5__social_graph` (`friendships`, `friend_closeness`, `blocks`, `invites`, `profiles.email` synced from the JWT `email` claim). Package `com.pm.filmfuse.social`: `SocialGraphService` is the only class touching graph tables (pair-scoped advisory locks for concurrent writes; others use `areFriends`/`friendIds`/`isBlockedEitherWay`/`closeness`). Endpoints: `/users/search`, `/users/{id}`, `/friends`, `/friends/requests`, `/friends/suggestions`, `/blocks`, `/invites` (contract in the plan). Friend-request/accepted emails go out after commit. `filmfuse.app.web-url` (`APP_WEB_URL`) is the base of invite and email links. The drift checker also compares Java enums against TS string unions.
  - Phase 3 groups/sessions/comfort (branch `phase-3-groups`, contract in the plan's Phase 3 section): Flyway `V6__movie_content` (`movies.certification` "G"…"NC-17" + `keywords`, `movie_content_levels`), `V7__user_settings` (comfort fields only), `V8__groups_and_sessions` (`groups`, `group_members`, `rec_sessions`, `rec_session_members/results/votes`, `watch_history`). DB stores certification/dimension/level in the ML contract's vocabulary ("PG-13", lowercase). Comfort: `GET/PUT /me/settings/comfort`; `reco.model.ContentFilter` holds the most-restrictive/eligibility rules, `ContentFilterService` builds the eligible pool in SQL (kept in step with the Java rule by `MovieContentRepositoryIntegrationTest`). TMDB details use `append_to_response=release_dates,keywords`; `KeywordContentLevelProvider` (one readable table) derives levels on every details upsert (`manual` rows win). Package `group`: `/groups`, `/sessions` (votes, refresh, pick, EXPIRED computed after 24 h). `GroupRecommender` = pool → `MlServiceClient` (`ML_SERVICE_URL`, `X-Internal-Key`, 5 s) or `FallbackRanker` (placeholder: popularity order, "Popular pick") → always re-filters results. `UserVectorLookup` returns nothing until Phase 1b (`hasVector=false`). Realtime: `RealtimeGateway` (Supabase broadcast / mock) fired after commit; RLS policy + `private.is_session_member` in `supabase/migrations` (not in `app`: Flyway refuses a non-empty schema). Account deletion publishes `AccountDeletionEvent` so owned groups are handed over first.
- `python-ml-service/`: frozen contract in `app/models/contract.py`, `PlaceholderRecommender` in `app/services/recommender.py` (owner swaps `get_recommender()`). Interpretable vector order = `GENRE_IDS` then `MOOD_TAGS` constants there; core-api must use the same mood-tag slugs.
- `client/`: Expo SDK 58 + Expo Router, auth flow (email/password), tab shell with empty states, Marquee theme, UI primitives. Onboarding redirect lives in `components/ProfileGate.tsx` (inside the `(tabs)` layout). Interim onboarding (until Phase 1b): `app/(onboarding)/onboarding.tsx` sets display name / username / photo, then `POST /api/v1/me/onboarding/complete` (requires a username; idempotent).
- `supabase/`: local CLI config (`supabase start`). `app` schema intentionally not in exposed API schemas.
- Legacy services (user/social/group/auth/movie/recommendation-service, Vite frontend) were deleted; see git history before this branch for the old Neo4j social-service code.
- Not yet provisioned: hosted Supabase project, Railway services, Vercel project, TMDB key (owner to set up).

## Workflow
- Plan before non-trivial work; the owner prefers plan mode for new features.
- Don't commit or push unless asked. Default branch is `master`.
