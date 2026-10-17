# Security

How FilmFuse is defended, what was reviewed in the Phase 6 hardening pass (2026-10-17), and how to
report a problem.

## Reporting a vulnerability

Please **do not open a public issue**. Email the maintainer at the address on the repository owner's
GitHub profile with "FilmFuse security" in the subject. Include the affected component, steps to
reproduce, and the impact you saw. You should get an acknowledgement within 3 working days. Please
give us a reasonable window to ship a fix before you disclose anything publicly. Never test against
other people's accounts or data. Use your own accounts on a local stack (`supabase start`).

## Threat model

| Asset | Where it lives | Who must not get it |
|---|---|---|
| Accounts (email, profile, photo) | Supabase Auth, `app.profiles`, Storage `profile-photos` | other users, the ML service (email) |
| Social graph (friends, blocks, invites) | `app.friendships`, `app.blocks`, `app.invites` (SHA-256 of tokens only) | anyone outside the pair; blocked users |
| Ratings, notes, comments, likes | `app.ratings`, `app.rating_comments`, `app.rating_likes` | anyone `RatingVisibilitySql` excludes |
| Groups and sessions | `app.groups`, `app.rec_session_*`, Realtime topic `session:<id>` | non-members |
| Secrets | Railway env (service-role key, DB password, ML key, Resend key, TMDB token) | everyone outside core-api / ML service |
| Email reputation and quota | Resend | spammers using our notification emails |

**Adversaries we design for:** an authenticated user who guesses or harvests ids (IDOR), a blocked or
suspended user trying to keep access, a script that floods writes to send email or exhaust
quotas, an unauthenticated client sending oversized or malformed requests, and a leaked ML-service
database credential.

**Trust boundaries:**
- Client to core-api: public internet, bearer JWT.
- core-api to Postgres: service credentials, owner of schema `app`.
- core-api to the ML service: Railway private network plus the `X-Internal-Key` header.
- ML service to Postgres: the read-only `ml_reader` role.
- Client to Supabase: Auth, public photo reads, and Realtime receive-only.

**Out of scope:** a compromised Railway, Supabase or Vercel account, and denial of service from many
distributed addresses. In-app rate limits only blunt the second; it needs an edge or WAF.

## Controls

### Authentication and authorisation (core-api)
- Supabase JWTs are verified against JWKS with ES256 only. Under `prod` the issuer must match and `aud`
  must be `authenticated`. `SupabaseJwtAuthenticationConverter` returns 401 for a non-UUID `sub`, a
  deleted account (tombstone) or a suspended one, checked on every request.
- Every resource is scoped to the caller in SQL or the service:
  - Groups and sessions are members-only. Strangers get a 404, so existence is never revealed. Owner-only
    actions return 403.
  - Comment deletion is limited to the comment's author or the rating's author.
  - Settings and preferences always use the caller's own id.
  - Photo paths must match `<callerId>/<uuid>.jpg`.
- `RatingVisibilitySql` is the only rule for who sees a rating, and it is reused for comments, comment
  counts, likes and reports. It covers visibility, blocks in both directions, suspended authors and
  hidden calibration swipes.
- Supabase: schema `app` is not in the Data API's exposed schemas.
  - Storage: the `profile-photos` bucket is public-read, JPEG only, 2 MB max. It has no
    `storage.objects` policies, so clients cannot write. Uploads use signed URLs that core-api issues.
  - Realtime: the topic policy calls `private.is_session_member`, which is `SECURITY DEFINER` with
    `search_path = ''` and receive-only.
- `ml_reader` has `SELECT` only. Since V16 it cannot read `profiles.email`, `invites` or `reports`.

### Abuse and input limits
- **Rate limiting** (`common.ratelimit`) uses in-memory token buckets that refill continuously.
  `RateLimitFilter` runs right after bearer authentication.
  - Limits: 600 req/min per client address and 120 req/min per user (JWT `sub`).
  - Per-user categories on top: friend requests 30/h, invites 20/h, reports 20/h, comments 60/h,
    session creation 30/h, photo upload URLs 20/h. Invites covers invite links, redeem,
    `POST /groups` and group invites.
  - Over the limit the response is 429 with `Retry-After` and `{message}`, carrying CORS headers.
  - Every limit is configurable (`filmfuse.rate-limit.limits.<name>.capacity|period`).
  - Memory is capped at 100k buckets, and buckets that have refilled are evicted.
  - **Single replica only**: N replicas would allow N times the limit.
- **Body cap** (`common.http.RequestBodyLimitFilter`) is 64 KB. A larger declared length gets a 413
  before authentication, and a chunked body gets a 413 while it is being read. List DTOs have explicit
  caps (19 group/session members, 20 genre filters, 50 calibration items, 10 favourites, one entry per
  genre or mood). Cursors are opaque, length-checked and parsed strictly (bad cursor = 400). Every
  `limit`/`count` parameter is bounded.
- **SQL**: every value is a bound parameter. The only interpolated fragments are compile-time
  constants (column lists, `RatingVisibilitySql`, `SocialGraphService.sql*`, an enum column name).
  `LIKE` input is escaped.
- **Email**: subjects have control characters replaced (no header injection). Bodies are plain text.

### Configuration, errors and headers
- `ProductionConfigValidator` stops a `prod` startup when any of these holds:
  - A DB or service-role secret is blank.
  - JWKS, issuer, Supabase or web URLs are not https, or point at a local host.
  - A CORS origin is a wildcard or not https.
  - The ML URL is set without a key, or SMTP is enabled without a password.
  - Rate limiting is off, or springdoc is on.

  Its messages name the property keys only, never the values.
- Responses:
  - Errors are `{message, fieldErrors?}`. Unexpected exceptions return a fixed sentence plus an error
    id, never a stack trace, SQL or parser text.
  - Logs carry ids, not emails or tokens.
  - Swagger and the OpenAPI document are off under `prod` and not permitted there.
  - Actuator exposes `health` and `info` only, with health details `never` in prod.
- Headers (Spring Security defaults plus our own):
  - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` and `Pragma: no-cache`.
  - `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and
    `Referrer-Policy: strict-origin-when-cross-origin`.
  - HSTS (2 years, includeSubDomains) over HTTPS.
  - A `default-src 'none'` CSP in prod.
- CORS uses exact origins with credentials off. It is a bearer-token API, so it has no cookies and no
  CSRF surface.

### python-ml-service
- `/internal/*` requires `X-Internal-Key`, compared with `hmac.compare_digest`. The key is mandatory
  unless `ENV=local`, and `ENV` defaults to `production`.
- `/docs`, `/redoc` and `/openapi.json` are disabled when `ENV=production`. `/health` stays public.
- `BodySizeLimitMiddleware` returns 413 above `ML_MAX_BODY_BYTES` (default 5 MiB), for declared and
  chunked bodies alike.
- Dependencies are pinned to exact versions, including starlette. The container runs as a non-root
  user.

## Phase 6 review findings

Severity reflects the impact if exploited in the current deployment. Line numbers refer to the code
before the fix.

| # | Sev | Finding | Location | Status |
|---|---|---|---|---|
| 1 | High | No rate limiting. A user could loop friend request, cancel, request to email any user without limit, and flood comment, invite and session emails, burning Resend quota and reputation. | `social/service/FriendRequestService.java:45`, `:83`; all write endpoints | **Fixed**: `RateLimiter` / `RateLimitFilter` |
| 2 | Med | No request body cap. A 10 MB body or a 10k-element array was fully deserialised. | e.g. `rating/controller/RatingController.java:64`, `user/controller/PreferencesController.java:33-45` | **Fixed**: 64 KB cap, 413 |
| 3 | Med | Under `prod`, a set-but-blank `SUPABASE_SERVICE_ROLE_KEY` silently selected the mock storage, auth-admin and realtime gateways. Account deletion would then leave the Supabase user behind. A blank `SUPABASE_URL` fell back to localhost. | `user/gateway/SupabaseGatewayConfig.java:26,36`, `group/realtime/RealtimeConfig.java:22` | **Fixed**: `ProductionConfigValidator` |
| 4 | Med | `ml_reader` could read every user's email, invite token hashes and moderation reports. | `db/migration/V2__ml_reader_role.sql:19,22` | **Fixed**: `V16__ml_reader_least_privilege` |
| 5 | Med | FastAPI `/docs` and `/openapi.json` were served in production. | `python-ml-service/app/main.py:12` | **Fixed** |
| 6 | Med | The ML service had no request body limit. | `python-ml-service/app/main.py` | **Fixed**: `BodySizeLimitMiddleware` |
| 7 | Low | Email subjects embed group and display names without stripping CR/LF, leaving a header-injection risk. | `group/service/GroupEmailNotifier.java:27`, `email/SmtpEmailGateway.java:33` | **Fixed**: `EmailMessage` sanitises |
| 8 | Low | The JWT audience was not checked. | `application-prod.properties:20` | **Fixed**: `audiences=authenticated` |
| 9 | Low | A `*` or http CORS origin was accepted in prod. | `common/config/SecurityConfig.java:94` | **Fixed**: validator |
| 10 | Low | Genre and mood lists were only implicitly bounded, and an unknown mood tag was echoed back whole. | `user/service/TastePreferencesService.java:93-131` | **Fixed**: caps + 40-char echo |
| 11 | Low | starlette was not pinned. | `python-ml-service/requirements.txt` | **Fixed** |
| 12 | Low | The IP bucket uses the first `X-Forwarded-For` entry (`forward-headers-strategy=framework`). If Railway appends to a client-supplied header rather than replacing it, a client can rotate its apparent IP. Per-user limits are unaffected. | `application-prod.properties:8` | Open: verify Railway's behaviour, or key on a trusted header |
| 13 | Low | Rate-limit state is in memory: it resets on restart and is not shared across replicas. | `common/ratelimit/RateLimiter.java` | Accepted (single replica) |
| 14 | Low | Session membership is a snapshot. Someone removed from a group, or blocked later, keeps read access to past sessions they were in. | `group/service/SessionService.java:297` | Open (by design; revisit) |
| 15 | Low | Group and session member lists include members who have blocked the viewer. | `group/service/GroupService.java:321` | Open |
| 16 | Low | `likeCount` counts likes from users blocked either way. | `rating/repository/FeedRepository.java:35` | Open |
| 17 | Low | Incoming friend requests from accounts suspended later are still listed. | `social/repository/FriendshipRepository.java:282` | Open |
| 18 | Low | A failed send logs the mail exception, whose provider message can include the recipient address. | `social/service/NotificationEmailSender.java:81` | Open |
| 19 | Low | Display and group names accept control and bidi characters, which allows render-level spoofing. | `user/dto/UpdateProfileRequestDTO.java`, `group/dto/CreateGroupDTO.java` | Open |
| 20 | Low | `ML_INTERNAL_KEY` has no minimum length or entropy check. | `python-ml-service/app/core/config.py` | Open |
| 21 | Low | The invite token is in the URL path, so it can appear in proxy access logs. | `social/controller/InviteController.java:31` | Open |
| 22 | Low | Photo confirmation does not check that the object exists in storage, though it is limited to the caller's own prefix. | `user/service/ProfilePhotoService.java:49` | Open |
| 23 | Info | `PENDING_VERIFICATION` account status is not enforced anywhere. | `user/model/AccountStatus.java` | Open |
| 24 | Info | Python pins have no hashes (`--require-hashes`). Maven versions come from the Boot BOM. | `requirements.txt`, `pom.xml` | Open |

**Checked with nothing found:**
- IDOR on groups, sessions (get/vote/refresh/pick/list by `groupId`), ratings, comments, likes,
  reports, invites, photos and settings.
- Block and suspension enforcement in search, profiles, feed, suggestions and comments.
- SQL binding and cursor tampering.
- Exception text leaking to clients, and secrets in logs.
- Actuator and Swagger exposure.
- The Supabase schemas exposed through the Data API, the bucket and Realtime policies.
- `ml_reader` write access and the key comparison.

## Tests

- **core-api**:
  - `RateLimiterTest`, `RateLimitRuleTest` and `RateLimitIntegrationTest`: every category, `Retry-After`,
    refill and reset, user vs IP buckets, unauthenticated traffic, health and preflight exemptions,
    CORS on 429.
  - `RequestBodyLimitFilterTest` and `RequestBodyLimitIntegrationTest`: declared and chunked bodies,
    10k-element arrays, 413 before authentication.
  - `ProductionConfigValidatorTest`, `EmailMessageTest` and `MigrationIntegrationTest` (ml_reader).
  - Header and error-leak tests in `SecurityIntegrationTest`, list caps in `PreferencesIntegrationTest`.
- **python-ml-service**: `tests/test_security.py` covers docs off in production, `/health` public,
  413 for declared and chunked bodies, and the `ENV` default.
