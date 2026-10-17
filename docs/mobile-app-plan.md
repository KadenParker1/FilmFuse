# FilmFuse mobile app plan

Proposed 2026-10-17.

## Context
`client/` is already one Expo universal codebase for iOS, Android and web:
- Expo SDK 58 with Expo Router.
- Native auth storage via SecureStore.
- Native Apple sign-in.
- Image picker and manipulator.
- Bottom tabs.

So the "mobile app" is not a rebuild. It is taking the same app from *runs in Expo Go* to *shipped in the App Store and Play Store*, with the native polish and platform requirements that come with that.

What's missing today:
- **Store setup:** no `eas.json`, no push notifications, no OTA updates.
- **Branding:** the icons and splash are Expo's template assets.
- **Links:** no universal or app links.
- **Moderation:** reports are only stored; nobody can act on them yet.
- **Backend:** no hosted backend. Reviewers need a live one.

## Phases

### M0 — Device dev loop (no accounts needed)
**Run it on real devices:**
- Run on iOS Simulator, Android emulator and a physical phone via Expo Go.
- Add a development build (`expo-dev-client`) once native config diverges.

**Point devices at the dev machine:**
- `localhost` doesn't work from a phone. Derive the API and Supabase host from `Constants.expoConfig.hostUri` in development, or use a tunnel.
- Add `filmfuse://**` and the dev LAN URL to the Supabase redirect URLs.

**Device end-to-end tests:**
- Use Maestro flows: sign up → onboarding → rate → friend → group session vote. They run against the local stack (`tools/e2e`).
- Add them to CI later, on an emulator.

**Exit:** the full flow works on a physical iPhone and Android phone against the local stack.

### M1 — Native polish
- **Gestures:** the calibration deck and "For you" become true swipe cards, using `react-native-gesture-handler` and Reanimated (already installed).
- **Haptics:** on votes, ratings and picks (`expo-haptics`).
- **Native bottom sheets:** quick-rate, report and confirm sheets. Add keyboard avoidance on the comment and onboarding forms.
- **Offline and poor network:**
  - `@react-native-community/netinfo` banner.
  - TanStack Query persisted cache (AsyncStorage) so the last feed, groups and profile render instantly.
  - Queued retries for ratings.
- **Accessibility:** VoiceOver and TalkBack pass, dynamic type, reduce motion (already respected on web).
- **Tablets:** `supportsTablet` is currently `true`. Either design the wide layout (side rail already exists) or set it to false for v1.

**Exit:** a usability pass on both platforms; Maestro flows green.

### M2 — Push notifications
The killer use case is "Kaden started a session, come vote".

**Client:**
- `expo-notifications`.
- Permission prompt *after* the first group or session, not at launch.
- Token registration on sign-in.
- Notification taps deep-link to `/sessions/{id}`, `/user/{id}`, `/ratings/{id}`.
- Badge counts for pending friend requests and invites.

**Backend:**
- `POST /me/push-tokens` and `DELETE /me/push-tokens/{token}`, stored in a `push_tokens` table.
- `PushGateway` via the **Expo Push API**, mocked locally.
- Handle receipts and prune dead tokens.

**Preferences:** each email preference gets a push sibling. The existing `NotificationEmailSender` becomes a `NotificationDispatcher` (email + push).

**Credentials:**
- APNs key via EAS (needs an Apple Developer account).
- FCM v1 service account for Android (needs a Firebase project).

**Exit:** the vote notification arrives on both platforms and opens the session.

### M3 — Links and identity (needs the domain from Phase 2)
- **Universal links (iOS):** Associated Domains, with `apple-app-site-association` served from the Vercel web app.
- **App links (Android):** `assetlinks.json` with `autoVerify`.
- **Domain links:** invite, session, profile and rating links open the app when it's installed, and the web otherwise. The web routes already exist.
- **Sign-in:**
  - Google sign-in keeps the PKCE browser flow. Consider the native Google SDK later.
  - Apple sign-in needs an App ID with the capability, plus a Services ID for web.

**Exit:** tapping an invite link from Messages opens the app straight to the invite.

### M4 — Release engineering
- **Accounts:** Apple Developer ($99/yr), Google Play ($25 once), Expo/EAS (the free tier is enough to start).
- **`eas.json` profiles:**
  - `development` (dev client, internal)
  - `preview` (internal distribution, staging backend)
  - `production` (store, prod backend)

  Each profile carries its own `EXPO_PUBLIC_*` env.
- **Versioning:** `appVersionSource: remote` with auto-incremented build numbers.
- **OTA updates:** `expo-updates` with one channel per profile and `runtimeVersion: { policy: "fingerprint" }`. JS-only fixes ship without store review.
- **Branding:** Marquee app icon, adaptive icons, splash and store screenshots. Replace the template assets.
- **Crash reporting:** Sentry (`@sentry/react-native`) for crashes and performance. Optional but recommended.
- **CI:** a GitHub Action runs `eas build` and `eas submit` on version tags. TestFlight internal and Play internal testing track first.
- **Prerequisite:** the backend is deployed (Supabase project, Railway core-api and ML service). Store review needs a live backend and a demo account.

**Exit:** a TestFlight build and a Play internal build that testers install from links.

### M5 — Store compliance and launch
- **Policies:** privacy policy and terms pages, hosted on the web app.
- **Data disclosures:** App Store privacy labels and Play Data Safety. Data collected: email, name, photo, ratings and notes, social graph, usage.
- **Account deletion:** in-app is ✅ (`DELETE /me`). Play also needs a web deletion URL.
- **User-generated content (Apple 1.2, Play UGC policy):**
  - Report ✅ and block ✅.
  - **Still needed:** a moderation workflow. Add admin endpoints (or a minimal admin page) to review reports, hide a rating or comment, suspend a user and notify the reporter, with a target of acting within 24 hours. Plus a content filter on comments.
- **Sign in with Apple** ✅ (required because Google is offered). TMDB attribution ✅.
- **Age rating:** UGC plus R-rated movie data suggests 12+ / Teen.
- **Export compliance:** HTTPS only, so exempt.
- **Review notes:** the demo account and how to start a session.

**Exit:** both apps approved; staged rollout (Play 10% → 100%, App Store phased release).

## Decisions needed from the owner
1. **Tablets:** design an iPad layout for v1, or phones only?
2. **Push provider:** Expo Push API (recommended; no extra vendor), or FCM/APNs direct?
3. **Crash reporting:** Sentry or none for v1?
4. **Identifiers:** bundle ID and package `com.pm.filmfuse`. These are permanent once published.
5. **Launch platforms:** both stores together, or Android first (cheaper, faster review)?
6. **Moderation:** who moderates reports at launch, and through what UI (admin page in the web app vs. a Supabase Studio query)?

## Suggested order
**Now, with no accounts:**
- M0 and M1.
- The M2 backend work (tokens table, dispatcher, Expo push in dev).
- The M5 moderation workflow.

**Then:**
- Buy the domain and deploy the backend.
- M3 and M4 (accounts).
- M5 (submission).
