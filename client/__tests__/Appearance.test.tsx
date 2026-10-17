jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(() => Promise.resolve(null)),
  setItemAsync: jest.fn(() => Promise.resolve()),
}));

import * as RN from 'react-native';
import { Text } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AppearanceSettingsScreen from '../app/settings/appearance';
import { stackScreenOptions } from '../theme/navigation';
import * as nativeStorage from '../lib/appearanceStorage.native';
import { APPEARANCE_KEY, DEFAULT_APPEARANCE, isAppearancePreference, resolveScheme } from '../lib/appearancePreference';
import { AppearanceProvider, useAppearance } from '../theme/AppearanceProvider';
import { palettes } from '../theme/tokens';
import { statusBarStyle, useTheme } from '../theme/useTheme';

const webStorage = jest.requireActual<typeof import('../lib/appearanceStorage')>('../lib/appearanceStorage.ts');
const getItem = SecureStore.getItemAsync as jest.Mock;
const setItem = SecureStore.setItemAsync as jest.Mock;

let systemScheme: 'light' | 'dark' | null = 'light';
beforeEach(() => {
  systemScheme = 'light';
  jest.spyOn(RN, 'useColorScheme').mockImplementation(() => systemScheme);
  getItem.mockReset().mockResolvedValue(null);
  setItem.mockClear();
});
afterEach(() => jest.restoreAllMocks());

function Probe() {
  const { scheme, colors } = useTheme();
  const { preference } = useAppearance();
  return (
    <Text testID="probe">
      {scheme}|{preference}|{colors.bg}
    </Text>
  );
}

const flush = () => act(async () => {});

describe('appearance preference', () => {
  it('resolves dark / light / system', () => {
    expect(DEFAULT_APPEARANCE).toBe('dark');
    expect(resolveScheme('dark', 'light')).toBe('dark');
    expect(resolveScheme('light', 'dark')).toBe('light');
    expect(resolveScheme('system', 'light')).toBe('light');
    expect(resolveScheme('system', 'dark')).toBe('dark');
    // Unknown system scheme stays dark-first.
    expect(resolveScheme('system', null)).toBe('dark');
    expect(isAppearancePreference('system')).toBe(true);
    expect(isAppearancePreference('sepia')).toBe(false);
  });

  it('the status bar follows the scheme', () => {
    expect(statusBarStyle('dark')).toBe('light');
    expect(statusBarStyle('light')).toBe('dark');
  });
});

describe('AppearanceProvider', () => {
  it('is dark by default even when the system is light', async () => {
    await render(
      <AppearanceProvider>
        <Probe />
      </AppearanceProvider>,
    );
    await flush();
    expect(getItem).toHaveBeenCalledWith(APPEARANCE_KEY);
    expect(screen.getByTestId('probe')).toHaveTextContent(`dark|dark|${palettes.dark.bg}`);
  });

  it('is dark outside a provider too (components rendered on their own)', async () => {
    await render(<Probe />);
    expect(screen.getByTestId('probe')).toHaveTextContent(/^dark\|dark\|/);
  });

  it('applies the saved choice once read', async () => {
    getItem.mockResolvedValue('light');
    await render(
      <AppearanceProvider>
        <Probe />
      </AppearanceProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent(`light|light|${palettes.light.bg}`));
  });

  it('"System" follows the device scheme', async () => {
    getItem.mockResolvedValue('system');
    systemScheme = 'dark';
    const { rerender } = await render(
      <AppearanceProvider>
        <Probe />
      </AppearanceProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent(/^dark\|system\|/));
    systemScheme = 'light';
    await rerender(
      <AppearanceProvider>
        <Probe />
      </AppearanceProvider>,
    );
    expect(screen.getByTestId('probe')).toHaveTextContent(/^light\|system\|/);
  });

  it('ignores a garbage stored value', async () => {
    getItem.mockResolvedValue('purple');
    await render(
      <AppearanceProvider>
        <Probe />
      </AppearanceProvider>,
    );
    await flush();
    expect(screen.getByTestId('probe')).toHaveTextContent(/^dark\|dark\|/);
  });
});

describe('Settings → Appearance', () => {
  const renderScreen = () =>
    render(
      <AppearanceProvider>
        <AppearanceSettingsScreen />
        <Probe />
      </AppearanceProvider>,
    );

  it('offers Dark / Light / System with Dark selected by default', async () => {
    await renderScreen();
    await flush();
    expect(screen.getByRole('header', { name: 'Appearance' })).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'Dark' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Light' })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: 'System' })).not.toBeChecked();
  });

  it('switches the app scheme and saves the choice on the device', async () => {
    await renderScreen();
    await flush();
    await fireEvent.press(screen.getByRole('radio', { name: 'Light' }));
    expect(screen.getByRole('radio', { name: 'Light' })).toBeChecked();
    expect(screen.getByTestId('probe')).toHaveTextContent(/^light\|light\|/);
    expect(setItem).toHaveBeenCalledWith(APPEARANCE_KEY, 'light');
    await fireEvent.press(screen.getByRole('radio', { name: 'System' }));
    expect(setItem).toHaveBeenLastCalledWith(APPEARANCE_KEY, 'system');
    expect(screen.getByTestId('probe')).toHaveTextContent(/^light\|system\|/); // system is light here
    await fireEvent.press(screen.getByRole('radio', { name: 'Dark' }));
    expect(screen.getByTestId('probe')).toHaveTextContent(/^dark\|dark\|/);
  });

  it('a choice made before the saved value loads wins', async () => {
    let resolve: (v: string) => void = () => {};
    getItem.mockReturnValue(new Promise((r) => (resolve = r)));
    await renderScreen();
    await fireEvent.press(screen.getByRole('radio', { name: 'Light' }));
    await act(async () => resolve('dark'));
    expect(screen.getByTestId('probe')).toHaveTextContent(/^light\|light\|/);
  });
});

describe('appearance storage', () => {
  it('native: SecureStore round trip, unknown values read as null, failures are swallowed', async () => {
    getItem.mockResolvedValueOnce('light');
    await expect(nativeStorage.getAppearance()).resolves.toBe('light');
    getItem.mockResolvedValueOnce('nope');
    await expect(nativeStorage.getAppearance()).resolves.toBeNull();
    getItem.mockRejectedValueOnce(new Error('locked'));
    await expect(nativeStorage.getAppearance()).resolves.toBeNull();
    await nativeStorage.setAppearance('system');
    expect(setItem).toHaveBeenCalledWith(APPEARANCE_KEY, 'system');
    setItem.mockRejectedValueOnce(new Error('full'));
    await expect(nativeStorage.setAppearance('dark')).resolves.toBeUndefined();
  });

  describe('web (localStorage)', () => {
    const g = global as unknown as { window?: { localStorage?: Storage } };
    const originalWindow = g.window;
    afterEach(() => {
      g.window = originalWindow;
    });

    it('round-trips and rejects unknown values', async () => {
      const store = new Map<string, string>();
      g.window = {
        localStorage: {
          getItem: (k: string) => store.get(k) ?? null,
          setItem: (k: string, v: string) => void store.set(k, v),
        } as Storage,
      };
      await expect(webStorage.getAppearance()).resolves.toBeNull();
      await webStorage.setAppearance('light');
      expect(store.get(APPEARANCE_KEY)).toBe('light');
      await expect(webStorage.getAppearance()).resolves.toBe('light');
      store.set(APPEARANCE_KEY, '<script>');
      await expect(webStorage.getAppearance()).resolves.toBeNull();
    });

    it('is a no-op without localStorage (static render) or when it throws', async () => {
      g.window = undefined;
      await expect(webStorage.getAppearance()).resolves.toBeNull();
      await expect(webStorage.setAppearance('dark')).resolves.toBeUndefined();
      g.window = {
        localStorage: {
          getItem: () => {
            throw new Error('blocked');
          },
          setItem: () => {
            throw new Error('blocked');
          },
        } as unknown as Storage,
      };
      await expect(webStorage.getAppearance()).resolves.toBeNull();
      await expect(webStorage.setAppearance('dark')).resolves.toBeUndefined();
    });
  });
});

describe('root stack (bug 5: clicks reaching the screen underneath)', () => {
  it('gives every stack screen an opaque background, never transparent', () => {
    const opts = stackScreenOptions(palettes.dark.bg);
    expect(opts.contentStyle.backgroundColor).toBe(palettes.dark.bg);
    expect(opts.contentStyle.backgroundColor).not.toBe('transparent');
    expect(opts.headerShown).toBe(false);
  });

  it('no layout in app/ sets a transparent stack card', () => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const fs = require('fs') as typeof import('fs');
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const path = require('path') as typeof import('path');
    const appDir = path.join(__dirname, '..', 'app');
    const layouts: string[] = [];
    const walk = (d: string) =>
      fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
        const p = path.join(d, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name === '_layout.tsx') layouts.push(p);
      });
    walk(appDir);
    expect(layouts.length).toBeGreaterThanOrEqual(3);
    for (const l of layouts) {
      expect({ l, transparent: /backgroundColor:\s*'transparent'/.test(fs.readFileSync(l, 'utf8')) }).toEqual({
        l,
        transparent: false,
      });
    }
  });
});
