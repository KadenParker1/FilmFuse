import fs from 'fs';
import path from 'path';
import type { ConfigContext, ExpoConfig } from 'expo/config';
import appConfig, {
  APP_ID,
  APP_SCHEME,
  BRAND_BACKGROUND,
  PERMISSION_TEXT,
  SUPPORTS_TABLET,
  assertBuildEnv,
  buildEnvProblems,
  createConfig,
} from '../app.config';
import { palettes } from '../theme/tokens';

const ROOT = path.join(__dirname, '..');

/** Width, height and whether a PNG has an alpha channel, from its IHDR chunk. */
function pngInfo(file: string) {
  const buf = fs.readFileSync(path.join(ROOT, file));
  expect(buf.subarray(1, 4).toString('ascii')).toBe('PNG');
  const colorType = buf[25];
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), alpha: colorType === 4 || colorType === 6 };
}

type PluginEntry = string | [string, Record<string, unknown>];
const pluginOptions = (config: ExpoConfig, name: string) => {
  const entry = (config.plugins as PluginEntry[]).find((p) => (Array.isArray(p) ? p[0] : p) === name);
  return Array.isArray(entry) ? entry[1] : entry;
};

describe('app config', () => {
  const config = createConfig();

  it('uses one identifier for iOS and Android and the filmfuse:// scheme', () => {
    expect(APP_ID).toBe('com.pm.filmfuse');
    expect(config.ios?.bundleIdentifier).toBe(APP_ID);
    expect(config.android?.package).toBe(APP_ID);
    expect(config.scheme).toBe(APP_SCHEME);
    expect(APP_SCHEME).toBe('filmfuse');
  });

  it('has a semver version and integer build numbers', () => {
    expect(config.version).toMatch(/^\d+\.\d+\.\d+$/);
    expect(config.ios?.buildNumber).toMatch(/^\d+$/);
    expect(Number.isInteger(config.android?.versionCode)).toBe(true);
  });

  it('is phone-only, portrait, and follows the OS scheme so "System" appearance works', () => {
    expect(SUPPORTS_TABLET).toBe(false);
    expect(config.ios?.supportsTablet).toBe(false);
    expect(config.orientation).toBe('portrait');
    expect(config.userInterfaceStyle).toBe('automatic');
  });

  it('launches on the Technicolor dark background', () => {
    expect(BRAND_BACKGROUND).toBe(palettes.dark.bg);
    expect(config.backgroundColor).toBe(palettes.dark.bg);
    expect(config.android?.adaptiveIcon?.backgroundColor).toBe(palettes.dark.bg);
    const splash = pluginOptions(config, 'expo-splash-screen') as Record<string, unknown>;
    expect(splash).toMatchObject({
      image: './assets/splash-icon.png',
      backgroundColor: palettes.dark.bg,
      dark: { backgroundColor: palettes.dark.bg },
    });
  });

  it('explains camera and photo access in plain words and never asks for the microphone', () => {
    const picker = pluginOptions(config, 'expo-image-picker') as Record<string, unknown>;
    expect(picker.photosPermission).toBe(PERMISSION_TEXT.photos);
    expect(picker.cameraPermission).toBe(PERMISSION_TEXT.camera);
    expect(picker.microphonePermission).toBe(false);
    for (const text of Object.values(PERMISSION_TEXT)) {
      expect(text).toMatch(/^FilmFuse /);
      expect(text).toMatch(/profile picture/);
    }
    expect(config.android?.blockedPermissions).toEqual(
      expect.arrayContaining(['android.permission.RECORD_AUDIO', 'android.permission.SYSTEM_ALERT_WINDOW']),
    );
    // SecureStore holds the session without biometrics: no Face ID purpose string.
    expect(pluginOptions(config, 'expo-secure-store')).toEqual({ faceIDPermission: false });
  });

  it('declares export compliance (HTTPS only)', () => {
    expect(config.ios?.config?.usesNonExemptEncryption).toBe(false);
  });

  it('keeps the plugins the app needs', () => {
    const names = (config.plugins as PluginEntry[]).map((p) => (Array.isArray(p) ? p[0] : p));
    expect(names).toEqual(
      expect.arrayContaining([
        'expo-router',
        'expo-secure-store',
        'expo-splash-screen',
        'expo-apple-authentication',
        'expo-image-picker',
      ]),
    );
    expect(config.ios?.usesAppleSignIn).toBe(true);
  });

  it('keeps fields Expo passes in and adds no EAS project id until one is set', () => {
    const merged = createConfig({ extra: { router: { origin: false } } } as Partial<ExpoConfig>);
    expect(merged.extra).toEqual({ router: { origin: false } });
  });

  it('is what the default export returns', () => {
    const fromDefault = appConfig({ config: {} } as ConfigContext);
    expect(fromDefault.ios?.bundleIdentifier).toBe(APP_ID);
  });
});

describe('brand assets', () => {
  it.each([
    ['assets/icon.png', 1024],
    ['assets/android-icon-foreground.png', 1024],
    ['assets/android-icon-background.png', 1024],
    ['assets/android-icon-monochrome.png', 1024],
    ['assets/splash-icon.png', 1024],
    ['assets/favicon.png', 48],
  ])('%s is a square %ipx PNG', (file, size) => {
    const info = pngInfo(file);
    expect([info.width, info.height]).toEqual([size, size]);
  });

  it('the iOS icon is opaque (App Store rejects alpha) and the layered images are transparent', () => {
    expect(pngInfo('assets/icon.png').alpha).toBe(false);
    expect(pngInfo('assets/android-icon-foreground.png').alpha).toBe(true);
    expect(pngInfo('assets/android-icon-monochrome.png').alpha).toBe(true);
    expect(pngInfo('assets/splash-icon.png').alpha).toBe(true);
  });

  it('every asset the config references exists', () => {
    const config = createConfig();
    const files = [
      config.icon,
      config.android?.adaptiveIcon?.foregroundImage,
      config.android?.adaptiveIcon?.backgroundImage,
      config.android?.adaptiveIcon?.monochromeImage,
      config.web?.favicon,
      (pluginOptions(config, 'expo-splash-screen') as { image: string }).image,
    ];
    for (const file of files) {
      expect(file).toBeTruthy();
      expect(fs.existsSync(path.join(ROOT, file as string))).toBe(true);
    }
  });

  it('commits the SVG sources and the generator next to them', () => {
    for (const file of ['generate.py', 'icon.svg', 'android-icon-foreground.svg', 'splash-icon.svg', 'favicon.svg']) {
      expect(fs.existsSync(path.join(ROOT, 'assets/brand', file))).toBe(true);
    }
  });
});

describe('build environment checks', () => {
  const good = {
    EXPO_PUBLIC_API_BASE_URL: 'https://api.filmfuse.app/api/v1',
    EXPO_PUBLIC_SUPABASE_URL: 'https://abcd.supabase.co',
    EXPO_PUBLIC_SUPABASE_ANON_KEY: 'anon',
  };

  it('does not check development builds or local runs', () => {
    expect(buildEnvProblems('development', {})).toEqual([]);
    expect(buildEnvProblems(undefined, {})).toEqual([]);
  });

  it.each(['preview', 'production'])('accepts public https values for %s', (profile) => {
    expect(buildEnvProblems(profile, good)).toEqual([]);
  });

  it('rejects missing, http and local URLs and a missing anon key', () => {
    expect(buildEnvProblems('production', {})).toHaveLength(3);
    expect(
      buildEnvProblems('production', {
        ...good,
        EXPO_PUBLIC_API_BASE_URL: 'http://api.filmfuse.app/api/v1',
        EXPO_PUBLIC_SUPABASE_URL: 'https://127.0.0.1:55321',
      }),
    ).toEqual([expect.stringContaining('EXPO_PUBLIC_API_BASE_URL'), expect.stringContaining('EXPO_PUBLIC_SUPABASE_URL')]);
    expect(buildEnvProblems('preview', { ...good, EXPO_PUBLIC_API_BASE_URL: 'https://localhost:8090/api/v1' })).toEqual([
      expect.stringContaining('EXPO_PUBLIC_API_BASE_URL'),
    ]);
    expect(buildEnvProblems('preview', { ...good, EXPO_PUBLIC_API_BASE_URL: 'https://192.168.1.20/api' })).toHaveLength(1);
    expect(buildEnvProblems('preview', { ...good, EXPO_PUBLIC_API_BASE_URL: 'not a url' })).toHaveLength(1);
  });

  it('fails only on the EAS build worker', () => {
    expect(() => assertBuildEnv({ EAS_BUILD_PROFILE: 'production' })).not.toThrow();
    expect(() => assertBuildEnv({ EAS_BUILD_RUNNER: 'eas-build', EAS_BUILD_PROFILE: 'production' })).toThrow(
      /eas env:create --environment production/,
    );
    expect(() => assertBuildEnv({ EAS_BUILD_RUNNER: 'eas-build', EAS_BUILD_PROFILE: 'production', ...good })).not.toThrow();
    expect(() => assertBuildEnv({ EAS_BUILD_RUNNER: 'eas-build', EAS_BUILD_PROFILE: 'development' })).not.toThrow();
  });
});

describe('eas.json', () => {
  const eas = JSON.parse(fs.readFileSync(path.join(ROOT, 'eas.json'), 'utf8'));

  it('has development, preview and production profiles, each with its own EAS environment', () => {
    expect(eas.build.development).toMatchObject({ developmentClient: true, distribution: 'internal', environment: 'development' });
    expect(eas.build.preview).toMatchObject({ distribution: 'internal', environment: 'preview' });
    expect(eas.build.production).toMatchObject({ distribution: 'store', environment: 'production', autoIncrement: true });
  });

  it('keeps build numbers on EAS and ships an app bundle to Play internal testing', () => {
    expect(eas.cli.appVersionSource).toBe('remote');
    expect(eas.build.production.android.buildType).toBe('app-bundle');
    expect(eas.submit.production.android.track).toBe('internal');
  });

  it('never carries secrets (values come from EAS environments)', () => {
    expect(JSON.stringify(eas)).not.toMatch(/SUPABASE|ANON_KEY|SERVICE_ROLE|API_BASE_URL/);
  });
});

describe('managed workflow', () => {
  it('ignores generated native folders and keeps no app.json beside app.config.ts', () => {
    const gitignore = fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf8');
    expect(gitignore).toMatch(/^\/ios$/m);
    expect(gitignore).toMatch(/^\/android$/m);
    expect(fs.existsSync(path.join(ROOT, 'app.json'))).toBe(false);
  });
});
