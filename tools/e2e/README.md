# End-to-end smoke tests (local stack)

Runs the real pieces together: local Supabase (Auth, Storage, Realtime), core-api and python-ml-service. Nothing is mocked except TMDB (fixture catalog when `TMDB_ACCESS_TOKEN` is unset) and email (mock gateway).

```bash
# 1. Infra (FilmFuse uses ports 553xx so it can run beside other local Supabase projects)
supabase start

# 2. ML service
cd python-ml-service && ENV=local .venv/bin/uvicorn app.main:app --port 8001 &

# 3. core-api on :8090 (8080 is often taken), pointed at local Supabase + ML
cd core-api && ./mvnw -q -DskipTests package
eval "$(supabase status -o env | grep -E '^(SERVICE_ROLE_KEY|ANON_KEY)=')"
SUPABASE_SERVICE_ROLE_KEY="$SERVICE_ROLE_KEY" ML_SERVICE_URL=http://127.0.0.1:8001 \
  ML_INTERNAL_KEY=local-dev-internal-key SERVER_PORT=8090 \
  java -jar target/core-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local &

# 4. API flow smoke test (signup → onboarding → vector via ML → friends → comfort → group session
#    → ratings/feed → For you → photo upload to Storage → account deletion)
ANON_KEY="$ANON_KEY" python3 -I tools/e2e/smoke.py

# 5. Realtime: a member receives session_updated on the private channel; an outsider is refused.
#    SESSION_ID and MEMBER_EMAIL come from a session created by step 4.
ANON_KEY="$ANON_KEY" MEMBER_EMAIL=... SESSION_ID=... node tools/e2e/realtime-smoke.mjs
```

Bugs these have caught so far (2026-10-17):
- **ML calls failed with 422.** The JDK HTTP client's HTTP/2 upgrade made uvicorn drop request bodies. Fixed by pinning HTTP/1.1 in `RestClients`.
- **Capped groups got empty sessions.** List endpoints never cached certifications, so groups with a certification cap got zero results. Fixed by catalog certification enrichment.
