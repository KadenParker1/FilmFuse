import type { ConfigContext, ExpoConfig } from 'expo/config';

/**
 * FilmFuse's Expo app config (replaces app.json so identity, versions and build-time checks live in
 * one typed place). Evaluated by `expo start`, `expo export`, `expo prebuild` and EAS Build.
 * Store setup and the owner's remaining steps: docs/mobile-app-plan.md.
 */

// ---------------------------------------------------------------------------------------------
// App identity - change it here and only here.
// ---------------------------------------------------------------------------------------------

/**
 * iOS bundle identifier and Android application id. PROVISIONAL: owner decision 4 in
 * docs/mobile-app-plan.md. Both are permanent once a build is uploaded to either store, so settle
 * this before the first `eas build` that is submitted.
 */
export const APP_ID = 'com.pm.filmfuse';
export const APP_NAME = 'FilmFuse';
/** `filmfuse://` deep links (auth callback, invites, sessions...). Supabase allows `filmfuse://**`. */
export const APP_SCHEME = 'filmfuse';
/** The user-facing version (CFBundleShortVersionString / versionName). Bump for each store release. */
export const APP_VERSION = '0.1.0';
/**
 * Build number / versionCode for local builds (`expo prebuild`, `expo run:*`). EAS ignores it:
 * eas.json uses `appVersionSource: "remote"` and auto-increments on every production build.
 */
export const BUILD_NUMBER = 1;
/**
 * iPad: phones only for v1 (owner decision 1, provisional). The wide layout (NavRail) exists, but
 * supporting iPad means all four orientations for multitasking, iPad screenshots and iPad review -
 * and once an App Store version supports iPad, later versions can't drop it. Turning it on later
 * is always allowed; iPads still run the iPhone app in compatibility mode meanwhile.
 */
export const SUPPORTS_TABLET = false;
/** Set after `eas init` (or `eas project:info`) - links builds to the Expo project. */
export const EAS_PROJECT_ID: string | undefined = undefined;

/** Launch / splash background: Technicolor dark `bg` (theme/tokens.ts; a test keeps them equal). */
export const BRAND_BACKGROUND = '#0B0D14';

/** Permission prompts (iOS purpose strings). Shown verbatim by the OS - say what and why. */
export const PERMISSION_TEXT = {
  photos: 'FilmFuse opens your photo library only when you choose a profile picture. Nothing else is read or uploaded.',
  camera: 'FilmFuse uses the camera only when you choose to take a new profile picture.',
} as const;

// ---------------------------------------------------------------------------------------------
// Build-time environment checks
// ---------------------------------------------------------------------------------------------

/** EAS Build profiles in eas.json. */
export type BuildProfile = 'development' | 'preview' | 'production';

type Env = Record<string, string | undefined>;

const LOCAL_HOST = /^(localhost|127\.\d+\.\d+\.\d+|10\.0\.2\.2|0\.0\.0\.0|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|\[::1\])$/i;

function isPublicHttps(value: string | undefined): boolean {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !LOCAL_HOST.test(url.hostname);
  } catch {
    return false;
  }
}

/**
 * What is wrong with the `EXPO_PUBLIC_*` values a store-bound build would bake in. `preview` and
 * `production` bundles run on testers' phones, so they need public https URLs and an anon key;
 * `development` builds talk to the dev machine and are not checked.
 */
export function buildEnvProblems(profile: string | undefined, env: Env): string[] {
  if (profile !== 'preview' && profile !== 'production') return [];
  const problems: string[] = [];
  if (!isPublicHttps(env.EXPO_PUBLIC_API_BASE_URL)) {
    problems.push('EXPO_PUBLIC_API_BASE_URL must be a public https URL (e.g. https://api.example.com/api/v1)');
  }
  if (!isPublicHttps(env.EXPO_PUBLIC_SUPABASE_URL)) {
    problems.push('EXPO_PUBLIC_SUPABASE_URL must be the hosted project URL (https://<ref>.supabase.co)');
  }
  if (!env.EXPO_PUBLIC_SUPABASE_ANON_KEY) problems.push('EXPO_PUBLIC_SUPABASE_ANON_KEY is not set');
  return problems;
}

/**
 * Fails the EAS build worker (not local `eas` commands, which may not see the environment's
 * values) before it bundles a preview/production app that points at localhost.
 */
export function assertBuildEnv(env: Env = process.env): void {
  if (env.EAS_BUILD_RUNNER !== 'eas-build') return;
  const problems = buildEnvProblems(env.EAS_BUILD_PROFILE, env);
  if (problems.length > 0) {
    throw new Error(
      `FilmFuse ${env.EAS_BUILD_PROFILE} build is missing its environment:\n- ${problems.join('\n- ')}\n` +
        `Set them with \`eas env:create --environment ${env.EAS_BUILD_PROFILE}\` (see docs/mobile-app-plan.md).`,
    );
  }
}

// ---------------------------------------------------------------------------------------------
// The config
// ---------------------------------------------------------------------------------------------

export function createConfig(base: Partial<ExpoConfig> = {}): ExpoConfig {
  return {
    ...base,
    name: APP_NAME,
    slug: 'filmfuse',
    scheme: APP_SCHEME,
    version: APP_VERSION,
    orientation: 'portrait',
    icon: './assets/icon.png',
    // "automatic", not "dark": the app is dark by default (theme/AppearanceProvider), but its
    // "System" appearance option reads the OS scheme, which "dark" would pin. Launch screens are
    // dark either way (splash backgroundColor below).
    userInterfaceStyle: 'automatic',
    backgroundColor: BRAND_BACKGROUND,
    ios: {
      bundleIdentifier: APP_ID,
      buildNumber: String(BUILD_NUMBER),
      supportsTablet: SUPPORTS_TABLET,
      usesAppleSignIn: true,
      // HTTPS only, no custom crypto: exempt from export compliance (skips the TestFlight question).
      config: { usesNonExemptEncryption: false },
    },
    android: {
      package: APP_ID,
      versionCode: BUILD_NUMBER,
      adaptiveIcon: {
        backgroundColor: BRAND_BACKGROUND,
        foregroundImage: './assets/android-icon-foreground.png',
        backgroundImage: './assets/android-icon-background.png',
        monochromeImage: './assets/android-icon-monochrome.png',
      },
      // Sheets close through Modal.onRequestClose (back button / gesture); keep the classic back
      // behaviour until predictive back is tested on a device.
      predictiveBackGestureEnabled: false,
      // The image picker never records video (no microphone), and nothing draws over other apps
      // (SYSTEM_ALERT_WINDOW comes from the React Native template).
      blockedPermissions: ['android.permission.RECORD_AUDIO', 'android.permission.SYSTEM_ALERT_WINDOW'],
    },
    web: {
      bundler: 'metro',
      output: 'static',
      favicon: './assets/favicon.png',
    },
    plugins: [
      'expo-router',
      // Keychain storage without biometrics: no Face ID prompt text to justify in review.
      ['expo-secure-store', { faceIDPermission: false }],
      'expo-font',
      [
        'expo-splash-screen',
        {
          image: './assets/splash-icon.png',
          imageWidth: 200,
          resizeMode: 'contain',
          backgroundColor: BRAND_BACKGROUND,
          dark: { image: './assets/splash-icon.png', backgroundColor: BRAND_BACKGROUND },
        },
      ],
      'expo-web-browser',
      'expo-image',
      'expo-apple-authentication',
      [
        'expo-image-picker',
        {
          photosPermission: PERMISSION_TEXT.photos,
          cameraPermission: PERMISSION_TEXT.camera,
          microphonePermission: false,
        },
      ],
      'expo-asset',
    ],
    experiments: {
      typedRoutes: true,
    },
    extra: {
      ...base.extra,
      ...(EAS_PROJECT_ID ? { eas: { projectId: EAS_PROJECT_ID } } : {}),
    },
  };
}

export default ({ config }: ConfigContext): ExpoConfig => {
  assertBuildEnv();
  return createConfig(config);
};
