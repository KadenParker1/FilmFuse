/**
 * The web app on iPhone Safari / "Add to Home Screen": safe-area insets, the document head,
 * the web app manifest and icons, the theme behind the app, and sheets above the iOS keyboard.
 */
import fs from 'fs';
import { useContext, type ReactElement } from 'react';
import path from 'path';
import { act, render, renderHook, screen } from '@testing-library/react-native';
import { Platform, Text } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';
import Root, { WEB_HEAD } from '../app/+html';
import { APP_NAME } from '../app.config';
import { WebSafeAreaProvider } from '../components/WebSafeAreaProvider';
import { WebDocumentTheme, applyDocumentTheme, documentThemeScript } from '../components/WebDocumentTheme';
import { Sheet, sheetBottomPadding, webSheetLayout } from '../components/ui/Sheet';
import {
  NO_VIEWPORT_GAP,
  useWebKeyboardViewport,
  visualViewportGap,
} from '../components/ui/useWebKeyboardViewport';
import { APPEARANCE_KEY } from '../lib/appearancePreference';
import { palettes } from '../theme/tokens';

const ROOT = path.join(__dirname, '..');

// react-dom ships without types here (no @types/react-dom); this is the one function needed.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { renderToStaticMarkup } = require('react-dom/server') as { renderToStaticMarkup: (element: ReactElement) => string };

function pngInfo(file: string) {
  const buf = fs.readFileSync(path.join(ROOT, file));
  expect(buf.subarray(1, 4).toString('ascii')).toBe('PNG');
  const colorType = buf[25];
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), alpha: colorType === 4 || colorType === 6 };
}

describe('WebSafeAreaProvider', () => {
  /** Whether a safe-area provider sits above (expo-router's own isn't rendered in these tests). */
  let provided: boolean | undefined;
  function Probe() {
    provided = useContext(SafeArea.SafeAreaInsetsContext) != null;
    return <Text>App</Text>;
  }
  beforeEach(() => {
    provided = undefined;
  });

  it('web: measures real insets with its own provider (expo-router hands out zeros there)', async () => {
    await render(
      <WebSafeAreaProvider os="web">
        <Probe />
      </WebSafeAreaProvider>,
    );
    expect(screen.getByText('App')).toBeOnTheScreen();
    expect(provided).toBe(true);
  });

  it.each(['ios', 'android'])('%s: adds nothing (expo-router already measures)', async (os) => {
    await render(
      <WebSafeAreaProvider os={os}>
        <Probe />
      </WebSafeAreaProvider>,
    );
    expect(screen.getByText('App')).toBeOnTheScreen();
    expect(provided).toBe(false);
  });
});

describe('document head (app/+html.tsx)', () => {
  const html = renderToStaticMarkup(
    <Root>
      <div id="root" />
    </Root>,
  );

  it('draws under the notch and keeps pinch zoom', () => {
    expect(html).toContain('<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>');
    expect(WEB_HEAD.viewport).not.toMatch(/maximum-scale|user-scalable/);
  });

  it('is a standalone home-screen web app with a translucent status bar', () => {
    expect(html).toContain('<link rel="manifest" href="/manifest.webmanifest"/>');
    expect(html).toContain('<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png"/>');
    expect(html).toContain('<meta name="apple-mobile-web-app-capable" content="yes"/>');
    expect(html).toContain('<meta name="mobile-web-app-capable" content="yes"/>');
    expect(html).toContain('<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/>');
    expect(html).toContain(`<meta name="apple-mobile-web-app-title" content="${APP_NAME}"/>`);
  });

  it('paints the theme background before JS and sets theme-color for both OS schemes', () => {
    expect(html).toContain(`html{background-color:${palettes.dark.bg}`);
    expect(html).toContain('(prefers-color-scheme: dark)');
    expect(html).toContain('(prefers-color-scheme: light)');
    expect(html.match(/<meta name="theme-color"/g)).toHaveLength(2);
  });

  it('fills the dynamic viewport instead of 100vh', () => {
    expect(html).toContain('height:100dvh');
    expect(html).not.toMatch(/100vh/);
  });

  it('renders the page content', () => {
    expect(html).toContain('<div id="root"></div>');
  });
});

describe('web app manifest and icons', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(ROOT, 'public/manifest.webmanifest'), 'utf8'));

  it('names FilmFuse, opens standalone at the root, and uses the dark theme', () => {
    expect(manifest).toMatchObject({
      name: 'FilmFuse',
      short_name: 'FilmFuse',
      display: 'standalone',
      start_url: '/',
      scope: '/',
      background_color: palettes.dark.bg,
      theme_color: palettes.dark.bg,
    });
  });

  it('lists 192 / 512 icons and a maskable 512 that exist at their sizes', () => {
    type Icon = { src: string; sizes: string; type: string; purpose?: string };
    const icons = manifest.icons as Icon[];
    expect(icons.map((i) => `${i.sizes} ${i.purpose ?? 'any'}`)).toEqual([
      '192x192 any',
      '512x512 any',
      '512x512 maskable',
    ]);
    for (const icon of icons) {
      expect(icon.type).toBe('image/png');
      const size = Number(icon.sizes.split('x')[0]);
      const info = pngInfo(path.join('public', icon.src));
      expect([info.width, info.height]).toEqual([size, size]);
      expect(info.alpha).toBe(false);
    }
  });

  it('has an opaque 180x180 apple-touch-icon', () => {
    const info = pngInfo('public/apple-touch-icon.png');
    expect([info.width, info.height, info.alpha]).toEqual([180, 180, false]);
  });

  it('Vercel serves the manifest as application/manifest+json', () => {
    const vercel = JSON.parse(fs.readFileSync(path.join(ROOT, 'vercel.json'), 'utf8'));
    type Rule = { source: string; headers: { key: string; value: string }[] };
    const rule = (vercel.headers as Rule[]).find((h) => h.source === '/manifest.webmanifest');
    expect(rule?.headers).toEqual(
      expect.arrayContaining([{ key: 'Content-Type', value: 'application/manifest+json; charset=utf-8' }]),
    );
    // No CSP yet; if one is added it must allow the manifest and icons.
    const csp = (vercel.headers as Rule[])
      .flatMap((h) => h.headers)
      .find((h) => h.key.toLowerCase() === 'content-security-policy');
    if (csp) {
      expect(csp.value).toMatch(/manifest-src[^;]*'self'/);
      expect(csp.value).toMatch(/img-src[^;]*'self'/);
    }
  });
});

describe('theme behind the app', () => {
  function fakeDocument() {
    const metas = [{ content: '' }, { content: '' }].map((m) => ({
      ...m,
      setAttribute(_: string, value: string) {
        this.content = value;
      },
    }));
    return {
      metas,
      documentElement: { style: {} as Record<string, string> },
      body: { style: {} as Record<string, string> },
      querySelectorAll: () => metas,
    };
  }

  it.each(['dark', 'light'] as const)('applies the %s background to html, body and theme-color', (scheme) => {
    const doc = fakeDocument();
    applyDocumentTheme(doc as unknown as Document, scheme);
    expect(doc.documentElement.style.backgroundColor).toBe(palettes[scheme].bg);
    expect(doc.documentElement.style.colorScheme).toBe(scheme);
    expect(doc.body.style.backgroundColor).toBe(palettes[scheme].bg);
    expect(doc.metas.map((m) => m.content)).toEqual([palettes[scheme].bg, palettes[scheme].bg]);
  });

  function runScript(saved: string | null, systemLight: boolean, storageThrows = false) {
    const doc = fakeDocument();
    const win = {
      localStorage: {
        getItem: (key: string) => {
          if (storageThrows) throw new Error('blocked');
          return key === APPEARANCE_KEY ? saved : null;
        },
      },
      matchMedia: (q: string) => ({ matches: systemLight && q.includes('light') }),
    };
    // The inline script reads the globals `window` / `document`.
    new Function('window', 'document', documentThemeScript())(win, doc);
    return doc;
  }

  it.each([
    [null, false, 'dark'],
    [null, true, 'dark'],
    ['dark', true, 'dark'],
    ['light', false, 'light'],
    ['system', true, 'light'],
    ['system', false, 'dark'],
  ] as const)('pre-paint script: saved %s, system light %s → %s', (saved, systemLight, expected) => {
    const doc = runScript(saved, systemLight);
    expect(doc.documentElement.style.backgroundColor).toBe(palettes[expected].bg);
    expect(doc.metas[0]!.content).toBe(palettes[expected].bg);
  });

  it('pre-paint script: blocked storage leaves the static dark default', () => {
    const doc = runScript('light', true, true);
    expect(doc.documentElement.style.backgroundColor).toBeUndefined();
  });

  it('native: the component touches nothing', async () => {
    await expect(render(<WebDocumentTheme scheme="light" os="ios" />)).resolves.toBeDefined();
  });
});

describe('iOS Safari keyboard (visual viewport)', () => {
  it('reports the part of the page hidden above and below the visible area', () => {
    expect(visualViewportGap(844, { height: 844, offsetTop: 0 })).toBe(NO_VIEWPORT_GAP);
    expect(visualViewportGap(844, { height: 508, offsetTop: 0 })).toEqual({ top: 0, bottom: 336 });
    expect(visualViewportGap(844, { height: 508, offsetTop: 120 })).toEqual({ top: 120, bottom: 216 });
    expect(visualViewportGap(844, null)).toBe(NO_VIEWPORT_GAP);
  });

  it('ignores a pinch-zoomed page', () => {
    expect(visualViewportGap(844, { height: 400, offsetTop: 100, scale: 2 })).toBe(NO_VIEWPORT_GAP);
  });

  describe('the hook on web', () => {
    type Listener = () => void;
    let listeners: Record<string, Listener[]>;
    let viewport: { height: number; offsetTop: number; scale: number; addEventListener: jest.Mock; removeEventListener: jest.Mock };
    const g = globalThis as unknown as { window?: unknown };
    let savedWindow: unknown;

    beforeEach(() => {
      listeners = {};
      viewport = {
        height: 844,
        offsetTop: 0,
        scale: 1,
        addEventListener: jest.fn((type: string, fn: Listener) => (listeners[type] = [...(listeners[type] ?? []), fn])),
        removeEventListener: jest.fn((type: string, fn: Listener) => {
          listeners[type] = (listeners[type] ?? []).filter((l) => l !== fn);
        }),
      };
      savedWindow = g.window;
      g.window = { innerHeight: 844, visualViewport: viewport };
    });
    afterEach(() => {
      g.window = savedWindow;
    });

    it('follows the keyboard opening and closing, and stops listening when disabled', async () => {
      const { result, rerender } = await renderHook(({ on }: { on: boolean }) => useWebKeyboardViewport(on, 'web'), {
        initialProps: { on: true },
      });
      expect(result.current).toBe(NO_VIEWPORT_GAP);

      viewport.height = 500;
      await act(() => listeners.resize!.forEach((l) => l()));
      expect(result.current).toEqual({ top: 0, bottom: 344 });

      viewport.offsetTop = 80;
      await act(() => listeners.scroll!.forEach((l) => l()));
      expect(result.current).toEqual({ top: 80, bottom: 264 });

      await rerender({ on: false });
      expect(result.current).toBe(NO_VIEWPORT_GAP);
      expect(listeners.resize).toEqual([]);
      expect(listeners.scroll).toEqual([]);
    });

    it('native never listens', async () => {
      const { result } = await renderHook(() => useWebKeyboardViewport(true, 'ios'));
      expect(result.current).toBe(NO_VIEWPORT_GAP);
      expect(viewport.addEventListener).not.toHaveBeenCalled();
    });
  });
});

describe('Sheet on web', () => {
  it('stays below the status bar and above the keyboard, and scrolls when too tall', () => {
    expect(webSheetLayout('web', 47, { top: 80, bottom: 300 })).toEqual({
      frame: { paddingTop: 127, paddingBottom: 300 },
      panel: { maxHeight: '100%' },
    });
    expect(webSheetLayout('ios', 47, { top: 80, bottom: 300 })).toBeNull();
    expect(webSheetLayout('android', 0, NO_VIEWPORT_GAP)).toBeNull();
  });

  it('pads for the home indicator only while the keyboard is down', () => {
    expect(sheetBottomPadding(34, 0)).toBe(42);
    expect(sheetBottomPadding(0, 0)).toBe(24);
    expect(sheetBottomPadding(34, 300)).toBe(24);
  });

  it('wraps the content in a scroll view on web only', async () => {
    jest.replaceProperty(Platform, 'OS', 'web');
    try {
      await render(
        <Sheet visible onClose={() => {}} title="Options">
          <Text>Body</Text>
        </Sheet>,
      );
      expect(screen.getByTestId('sheet-scroll')).toBeOnTheScreen();
    } finally {
      jest.restoreAllMocks();
    }
  });

  it('native sheets are unchanged (no extra scroll view)', async () => {
    await render(
      <Sheet visible onClose={() => {}} title="Options">
        <Text>Body</Text>
      </Sheet>,
    );
    expect(screen.getByText('Body')).toBeOnTheScreen();
    expect(screen.queryByTestId('sheet-scroll')).toBeNull();
  });
});
