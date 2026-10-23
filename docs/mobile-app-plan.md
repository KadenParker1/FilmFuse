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
- **Store setup:** no push notifications, no OTA updates. (`eas.json` ✅, see Status.)
- **Branding:** ✅ app icon, adaptive icon, splash and favicon. Store screenshots are still to do.
- **Links:** no universal or app links.
- **Moderation:** reports are only stored; nobody can act on them yet.
- **Backend:** no hosted backend. Reviewers need a live one.

## Status — mobile groundwork (branch `mobile-groundwork`, 2026-10-23)
The M0/M1 work that needs no paid accounts. Nothing here needs an Apple, Google or Expo login.

**Done**
- **App identity in one place:** `client/app.config.ts` replaces `app.json`. The top of the file holds every value the owner may change:
  - `APP_ID` (`com.pm.filmfuse`, provisional — decision 4), `APP_NAME`, `APP_SCHEME` (`filmfuse`).
  - `APP_VERSION` (0.1.0) and `BUILD_NUMBER` (local builds only; EAS keeps build numbers remotely).
  - `SUPPORTS_TABLET` (`false`, provisional — decision 1), `EAS_PROJECT_ID` (fill in after `eas init`), and the iOS permission text.
  - Also: portrait; `userInterfaceStyle: automatic` (the app is dark by default, and `automatic` keeps the "System" appearance option working); the dark launch background from `theme/tokens.ts` (a test keeps them equal); `usesNonExemptEncryption: false` (HTTPS only, skips TestFlight's export-compliance question); no Face ID purpose string (SecureStore is used without biometrics); Android `RECORD_AUDIO` and `SYSTEM_ALERT_WINDOW` blocked.
  - `expo-system-ui` added (package.json + lockfile) so `userInterfaceStyle` / `backgroundColor` apply natively; prebuild warned without it.
- **Why phones only (provisional):** an iPad build must support all four orientations for multitasking and needs iPad screenshots and review. Once a store version supports iPad, later versions can't drop it. Turning it on later is always allowed; meanwhile iPads run the iPhone app.
- **Brand assets:** a "fused play" mark (a tangerine play triangle tucked behind a cobalt one) on the Technicolor blue-black.
  - Source SVGs and the generator live in `client/assets/brand/`. Regenerate with `python3 -I client/assets/brand/generate.py` (needs `rsvg-convert`, `brew install librsvg`).
  - Outputs in `client/assets/`: `icon.png` 1024 (opaque, as Apple requires); `android-icon-foreground/background/monochrome.png` 1024 (mark inside the 66/108 safe zone; monochrome for Android 13 themed icons); `splash-icon.png` 1024 (fits the Android 12 splash circle; shown at 200 dp on `#0B0D14`); `favicon.png` 48.
  - Review sheet: `client/assets/brand/preview.png`.
- **`client/eas.json`:**
  - `development` (dev client, internal, iOS simulator build) and `development-device` (same, for a registered iPhone).
  - `preview` (internal distribution: ad hoc iOS / APK) and `production` (store, AAB, `autoIncrement`). `appVersionSource: remote`.
  - Each profile names its EAS environment (`development` / `preview` / `production`), which supplies the `EXPO_PUBLIC_*` values. No URLs or keys are in the repo.
  - On the EAS build worker, `app.config.ts` refuses to build `preview` / `production` without public https API and Supabase URLs and an anon key, so a store build can't ship pointing at localhost.
  - `submit.production.android` targets the internal track as a draft.
- **M0 device dev loop:** `client/lib/devHost.ts`. In development, `localhost` / `127.0.0.1` API and Supabase URLs follow Metro's LAN host (`Constants.expoConfig.hostUri`). One `.env.local` then works in the browser, the simulator, an emulator and Expo Go on a phone. Local Supabase now also allows `exp://**` redirects for Expo Go auth callbacks (applies after `supabase stop && supabase start`).
- **Native-safety review** (regression tests in `client/__tests__/nativeSafety.test.tsx`):
  - **Web-only APIs:** every `window` / `document` / `localStorage` / `navigator` / `pagehide` use outside `*.web.ts(x)` was already guarded or had a `.native` twin (auth storage → chunked SecureStore, pending link → SecureStore, launch link → `Linking`, share → `Share.share`, OAuth → `openAuthSessionAsync` / native Apple). A test now scans the source, so a new unguarded use fails CI.
  - **Fixed — picker launched from a closing sheet (iOS):** Profile photo → "Choose from library" / "Take photo" closed the menu sheet and launched the picker in the same tick. UIKit can't present over a Modal that is still animating out, so on iOS the picker silently never opened. Group settings → "Delete group" → confirm sheet had the same problem. The new `components/ui/useSheetHandoff` waits for the sheet's `onDismiss` (600 ms fallback; immediate on Android and web).
  - **Fixed — keyboard in sheets (iOS):** in sheets with text fields (report details, rating note, group name, delete-account confirmation) you typed behind the keyboard. `Sheet` now pads with a `KeyboardAvoidingView` on iOS; Android's Modal window resizes itself.
  - **Fixed — keyboard on scrolling screens (iOS):** `Screen scroll` sets `automaticallyAdjustKeyboardInsets`, so e.g. the comment box on a rating page scrolls above the keyboard. Sign-in, onboarding, reset password and edit profile already had keyboard avoidance.
  - **Already fine:**
    - The Android back button closes every sheet (`Modal.onRequestClose` in the one `Sheet` component); `predictiveBackGestureEnabled: false`.
    - Safe areas come from `Screen` / `Sheet` / `Toast` / `NavRail` insets.
    - The camera permission is requested with an explanatory message on denial. The library uses the system photo picker, so there is no permission prompt.
    - Deep links (`filmfuse://…`, `exp://…/--/…`, https) are parsed by `pendingLinkPath.pathFromUrl` and allow-listed.
- **Validation:**
  - `expo prebuild --no-install --clean` in a scratch copy: no warnings. Info.plist has the purpose strings, `ITSAppUsesNonExemptEncryption=false` and `TARGETED_DEVICE_FAMILY=1`, and no Face ID key. The Android manifest removes the two blocked permissions.
  - `ios/` and `android/` stay out of the repo (managed workflow / CNG).
  - `npx expo-doctor`: 20/20.

**Remaining for M0–M1**
- **Simulator / emulator runs:** this Mac has only the Xcode Command Line Tools (no Xcode, no `simctl`) and no Android SDK. Nothing has run on a simulator, emulator or phone yet. First step with a device: Expo Go on an iPhone and an Android phone against the local stack.
- **Known dev-loop caveat:** core-api signs profile-photo URLs with the local Supabase host it was configured with (`127.0.0.1`). Photos won't load on a phone in local dev unless `SUPABASE_URL` uses the LAN IP. A hosted backend doesn't have this problem.
- **Unchanged from the plan:**
  - `expo-dev-client`: install it (`npx expo install expo-dev-client`) before the first `development` EAS build.
  - Maestro flows, swipe cards, haptics, the NetInfo banner, the persisted query cache, queued rating retries, and a VoiceOver / TalkBack pass.
  - `expo-updates`: channels plus `runtimeVersion`. Add `"channel"` to the preview / production profiles at the same time.
- **Device checks:** the Android keyboard in sheets and edge-to-edge insets.
- **Owner decisions 1–6** below are still open. The provisional defaults are `supportsTablet: false` and `com.pm.filmfuse`.

### Owner steps once accounts exist
1. **Decide the identifiers** (decision 4). If it isn't `com.pm.filmfuse`, change `APP_ID` in `client/app.config.ts` *before* step 6. It is permanent once uploaded. A reverse-DNS id on a domain you own (e.g. `app.filmfuse`) is the usual choice.
2. **Buy the accounts:**
   - Apple Developer Program ($99/yr, developer.apple.com/programs). Enrol as an organization if the store listing should show a company; that needs a D-U-N-S number.
   - Google Play Console ($25 once, play.google.com/console). New personal accounts must run a closed test with 12 testers for 14 days before production access.
   - Expo account (free).
3. **Link the project:** `npm i -g eas-cli`, then `cd client && eas login` and `eas init`. Copy the printed project id into `EAS_PROJECT_ID` in `app.config.ts`; `eas init` doesn't edit a dynamic config itself.
4. **Environments:** deploy the backend first (Supabase project, Railway core-api and ML service). Then, for `preview` and `production`:
   - `eas env:create --environment production --name EXPO_PUBLIC_API_BASE_URL --value https://<api>/api/v1 --visibility plaintext`
   - The same for `EXPO_PUBLIC_SUPABASE_URL` and `EXPO_PUBLIC_SUPABASE_ANON_KEY`. Both are public by design; never use a service-role key here.
   - Add `filmfuse://**` to the hosted Supabase Auth redirect URLs. Never `exp://**` there: that is for local Expo Go only, and any Expo Go project could receive the redirect.
5. **Dev client:** `npx expo install expo-dev-client` and commit it.
6. **First builds:** `eas build -p ios --profile development` (simulator) and `eas build -p android --profile preview`. On the first run EAS creates the iOS certificates and profiles and the Android keystore; let it manage them. Never commit `.p8` / `.p12` / `.jks` files (they're ignored).
7. **TestFlight:**
   - Create the app in App Store Connect with the bundle id from step 1.
   - Run `eas build -p ios --profile production`, then `eas submit -p ios --latest`.
   - Add internal testers under App Store Connect → TestFlight. Internal testing needs no review.
8. **Play internal testing:**
   - Create the app in Play Console and upload the first AAB by hand once (Play requires that before API uploads).
   - Create a Google service account key for `eas submit` (`eas credentials` → Android → Google Service Account).
   - Run `eas build -p android --profile production`, then `eas submit -p android --latest`. Per `eas.json`, it goes to the internal track as a draft.
   - Add testers by email list.
9. **Sign in with Apple:** enable the capability on the App ID (EAS does this because `usesAppleSignIn` is set). Configure the Apple provider in hosted Supabase (Services ID + key) for web.
10. **Then:** M2 (push credentials), M3 (domain links) and M5 (store listings, privacy labels, review notes with a demo account).

## Phases

### M0 — Device dev loop (no accounts needed)
**Run it on real devices:**
- Run on iOS Simulator, Android emulator and a physical phone via Expo Go.
- Add a development build (`expo-dev-client`) once native config diverges.

**Point devices at the dev machine:**
- `localhost` doesn't work from a phone. Derive the API and Supabase host from `Constants.expoConfig.hostUri` in development, or use a tunnel.
- Local Supabase allows `filmfuse://**` and `exp://**` (Expo Go on the LAN) redirects (`supabase/config.toml`); the hosted project gets `filmfuse://**` only.

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
- **Tablets:** `supportsTablet` is now `false` (provisional; see Status). Either design the wide layout (the side rail already exists) and turn it on, or keep v1 phones-only.

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
- **`eas.json` profiles** ✅ (written; see Status):
  - `development` (dev client, internal)
  - `preview` (internal distribution, staging backend)
  - `production` (store, prod backend)

  Each profile carries its own `EXPO_PUBLIC_*` env.
- **Versioning:** `appVersionSource: remote` with auto-incremented build numbers.
- **OTA updates:** `expo-updates` with one channel per profile and `runtimeVersion: { policy: "fingerprint" }`. JS-only fixes ship without store review.
- **Branding:** app icon, adaptive icons and splash ✅ (Technicolor "fused play", `client/assets/brand/`). Store screenshots are still to do.
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
1. **Tablets:** design an iPad layout for v1, or phones only? (Provisional: phones only, `SUPPORTS_TABLET` in `client/app.config.ts`.)
2. **Push provider:** Expo Push API (recommended; no extra vendor), or FCM/APNs direct?
3. **Crash reporting:** Sentry or none for v1?
4. **Identifiers:** bundle ID and package `com.pm.filmfuse`. These are permanent once published. (Provisional; `APP_ID` in `client/app.config.ts` is the only place to change them.)
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
