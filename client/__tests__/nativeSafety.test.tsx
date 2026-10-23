/**
 * Native-safety regressions: things that work on web and in jsdom-style tests but break, or quietly
 * misbehave, on a phone.
 */
import fs from 'fs';
import path from 'path';
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { Screen } from '../components/ui/Screen';
import { Sheet, sheetKeyboardBehavior } from '../components/ui/Sheet';
import { SHEET_DISMISS_FALLBACK_MS, useSheetHandoff } from '../components/ui/useSheetHandoff';

describe('useSheetHandoff', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('iOS: waits for the closing sheet to dismiss', async () => {
    const fn = jest.fn();
    const { result } = await renderHook(() => useSheetHandoff('ios'));
    await act(() => result.current.after(fn));
    expect(fn).not.toHaveBeenCalled();
    await act(() => result.current.onDismiss());
    expect(fn).toHaveBeenCalledTimes(1);
    // The fallback timer was cancelled: no second run.
    await act(() => jest.advanceTimersByTime(SHEET_DISMISS_FALLBACK_MS * 2));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('iOS: runs anyway if onDismiss never comes', async () => {
    const fn = jest.fn();
    const { result } = await renderHook(() => useSheetHandoff('ios'));
    await act(() => result.current.after(fn));
    await act(() => jest.advanceTimersByTime(SHEET_DISMISS_FALLBACK_MS - 1));
    expect(fn).not.toHaveBeenCalled();
    await act(() => jest.advanceTimersByTime(1));
    expect(fn).toHaveBeenCalledTimes(1);
    await act(() => result.current.onDismiss());
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('iOS: a newer hand-off replaces a pending one', async () => {
    const first = jest.fn();
    const second = jest.fn();
    const { result } = await renderHook(() => useSheetHandoff('ios'));
    await act(() => result.current.after(first));
    await act(() => result.current.after(second));
    await act(() => result.current.onDismiss());
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('iOS: an unmounted screen drops its pending hand-off', async () => {
    const fn = jest.fn();
    const { result, unmount } = await renderHook(() => useSheetHandoff('ios'));
    await act(() => result.current.after(fn));
    await unmount();
    await act(() => jest.advanceTimersByTime(SHEET_DISMISS_FALLBACK_MS));
    expect(fn).not.toHaveBeenCalled();
  });

  it.each(['android', 'web'])('%s: runs at once', async (os) => {
    const fn = jest.fn();
    const { result } = await renderHook(() => useSheetHandoff(os));
    await act(() => result.current.after(fn));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('an onDismiss with nothing pending is harmless', async () => {
    const { result } = await renderHook(() => useSheetHandoff('ios'));
    await expect(act(() => result.current.onDismiss())).resolves.not.toThrow();
  });
});

describe('Sheet on a phone', () => {
  it('pads above the iOS keyboard; Android resizes the Modal window itself', () => {
    expect(sheetKeyboardBehavior('ios')).toBe('padding');
    expect(sheetKeyboardBehavior('android')).toBeUndefined();
    expect(sheetKeyboardBehavior('web')).toBeUndefined();
  });

  /** The host Modal above `element` (props are what the native Modal receives). */
  type Instance = ReturnType<typeof screen.getByText>;
  function modalAbove(element: Instance): Instance {
    let node: Instance | null = element;
    while (node && String(node.type) !== 'Modal') node = node.parent as Instance | null;
    if (!node) throw new Error('no Modal above element');
    return node;
  }

  it('closes on the Android back button and reports when it has dismissed', async () => {
    const onClose = jest.fn();
    const onDismiss = jest.fn();
    await render(
      <Sheet visible onClose={onClose} onDismiss={onDismiss} title="Options">
        <Text>Body</Text>
      </Sheet>,
    );
    const modal = modalAbove(screen.getByText('Body'));
    await fireEvent(modal, 'requestClose');
    expect(onClose).toHaveBeenCalledTimes(1);
    await fireEvent(modal, 'dismiss');
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});

describe('Screen on a phone', () => {
  it('scrolls a focused field clear of the iOS keyboard', async () => {
    await render(
      <Screen scroll>
        <Text>Form</Text>
      </Screen>,
    );
    type Instance = ReturnType<typeof screen.getByText>;
    let node: Instance | null = screen.getByText('Form');
    while (node && String(node.type) !== 'RCTScrollView') node = node.parent as Instance | null;
    expect(node).not.toBeNull();
    expect(node!.props.automaticallyAdjustKeyboardInsets).toBe(true);
    expect(node!.props.keyboardShouldPersistTaps).toBe('handled');
  });
});

describe('web-only APIs in shared code', () => {
  const ROOT = path.join(__dirname, '..');
  const DIRS = ['app', 'api', 'auth', 'components', 'lib', 'theme'];
  const WEB_ONLY = /\b(window|document|localStorage|sessionStorage|navigator)\s*\.|addEventListener\('pagehide'/;
  const GUARD = /typeof (window|document|navigator) !== 'undefined'|Platform\.OS === 'web'/;

  function sourceFiles(dir: string): string[] {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return sourceFiles(full);
      return /\.(ts|tsx)$/.test(entry.name) && !/\.web\.tsx?$/.test(entry.name) ? [full] : [];
    });
  }

  it('every window/document/storage/navigator use outside *.web.ts(x) sits behind a guard', () => {
    const unguarded: string[] = [];
    for (const dir of DIRS) {
      for (const file of sourceFiles(path.join(ROOT, dir))) {
        const lines = fs.readFileSync(file, 'utf8').split('\n');
        lines.forEach((line, i) => {
          if (/^\s*(\/\/|\*|\/\*)/.test(line) || !WEB_ONLY.test(line)) return;
          const context = lines.slice(Math.max(0, i - 6), i + 1).join('\n');
          if (!GUARD.test(context)) unguarded.push(`${path.relative(ROOT, file)}:${i + 1}: ${line.trim()}`);
        });
      }
    }
    expect(unguarded).toEqual([]);
  });

  it('modules that need a browser have a native twin', () => {
    for (const base of ['lib/authStorage', 'lib/pendingLink', 'lib/launchLink', 'lib/appearanceStorage']) {
      expect(fs.existsSync(path.join(ROOT, `${base}.native.ts`))).toBe(true);
    }
    for (const base of ['components/social/shareInvite', 'components/sessions/shareSession', 'auth/oauth']) {
      expect(fs.existsSync(path.join(ROOT, `${base}.native.ts`))).toBe(true);
    }
  });
});
