import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, AppState, Platform, View } from 'react-native';
import { Toast } from './Toast';

/** How long an undo toast stays up. */
export const UNDO_TOAST_MS = 5_000;
/** How long the "Couldn't undo" follow-up stays up. */
export const UNDO_FAILED_MS = 3_000;
export const UNDO_FAILED_MESSAGE = 'Couldn’t undo that. Try again.';

/** Why an undo window closed without Undo. */
export type CommitReason = 'expired' | 'replaced' | 'hidden' | 'unmount' | 'background';

export type UndoOptions = {
  /** What just happened, e.g. "Removed from For you". */
  message: string;
  /** Reverses it. A rejected promise shows "Couldn't undo". */
  onUndo: () => void | Promise<unknown>;
  /** Button text; "Undo" by default. */
  undoLabel?: string;
  /** What a screen reader says for the button, e.g. "Undo removing Heat". */
  undoAccessibilityLabel?: string;
  /**
   * For deferred actions: runs once when the window closes without Undo — it timed out, another
   * toast replaced it, it was hidden, the provider unmounted, or the app went to the background.
   */
  onCommit?: (reason: CommitReason) => void;
};

type UndoToastApi = {
  /** Shows the toast (replacing, and so committing, any other) for {@link UNDO_TOAST_MS}; returns its key. */
  showUndo: (options: UndoOptions) => number;
  /** Hides it without undoing (committing it); with `key`, only if that toast is still the one up. */
  hideUndo: (key?: number) => void;
  /** A short error message in the same spot (no button), e.g. when a deferred action failed. */
  showError: (message: string) => void;
  /** Whether a toast is up (other toasts move out of its way). */
  visible: boolean;
};

type Shown = (UndoOptions & { key: number; failed?: false }) | { key: number; failed: true; message: string };

const UndoToastContext = createContext<UndoToastApi | null>(null);

export function useUndoToast(): UndoToastApi {
  const api = useContext(UndoToastContext);
  if (!api) throw new Error('useUndoToast must be used inside <UndoToastProvider>');
  return api;
}

/**
 * The app's undo toast: one at a time, floating above the tab bar, with an Undo button. Either the
 * action has already happened and Undo reverses it (`onUndo`), or the action is deferred: it happens
 * in `onCommit` once the window closes without Undo (timeout, replaced, hidden, unmount, app in the
 * background). Goes away after {@link UNDO_TOAST_MS}, on Undo, or when another one replaces it.
 */
export function UndoToastProvider({ children, bottomOffset }: { children: ReactNode; bottomOffset?: number }) {
  const [shown, setShown] = useState<Shown | null>(null);
  const nextKey = useRef(0);
  // The toast up right now, for commits that happen outside a render (timers, app state, unmount).
  const current = useRef<Shown | null>(null);
  // Toasts that are settled: undone or committed. Each commits at most once and never after Undo.
  const settled = useRef(new Set<number>());

  const show = useCallback((next: Shown | null) => {
    current.current = next;
    setShown(next);
  }, []);

  /** Closes the current toast's window without Undo. */
  const commitCurrent = useCallback((reason: CommitReason) => {
    const entry = current.current;
    if (!entry || entry.failed || settled.current.has(entry.key)) return;
    settled.current.add(entry.key);
    try {
      entry.onCommit?.(reason);
    } catch {
      // A failing commit reports itself (showError); it must not break the toast.
    }
  }, []);

  const hideUndo = useCallback(
    (key?: number) => {
      if (key != null && current.current?.key !== key) return;
      commitCurrent('hidden');
      show(null);
    },
    [commitCurrent, show],
  );
  const showUndo = useCallback(
    (options: UndoOptions) => {
      commitCurrent('replaced');
      nextKey.current += 1;
      show({ ...options, key: nextKey.current });
      // iOS has no live regions; announce it so VoiceOver users know Undo is there.
      AccessibilityInfo.announceForAccessibility?.(`${options.message}. ${options.undoLabel ?? 'Undo'} available.`);
      return nextKey.current;
    },
    [commitCurrent, show],
  );
  const showError = useCallback(
    (message: string) => {
      commitCurrent('replaced');
      nextKey.current += 1;
      show({ key: nextKey.current, failed: true, message });
    },
    [commitCurrent, show],
  );

  useEffect(() => {
    if (!shown) return;
    const t = setTimeout(
      () => {
        if (current.current?.key !== shown.key) return;
        commitCurrent('expired');
        show(null);
      },
      shown.failed ? UNDO_FAILED_MS : UNDO_TOAST_MS,
    );
    return () => clearTimeout(t);
  }, [shown, commitCurrent, show]);

  // Leaving the app closes the window (best effort: a page being closed may not finish the request).
  useEffect(() => {
    const flush = () => {
      if (!current.current || current.current.failed) return;
      commitCurrent('background');
      show(null);
    };
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'background' || state === 'inactive') flush();
    });
    const web = Platform.OS === 'web' && typeof window !== 'undefined' && typeof window.addEventListener === 'function';
    if (web) window.addEventListener('pagehide', flush);
    return () => {
      // The jest mock of AppState returns no subscription.
      sub?.remove?.();
      if (web) window.removeEventListener('pagehide', flush);
    };
  }, [commitCurrent, show]);

  useEffect(() => () => commitCurrent('unmount'), [commitCurrent]);

  const undo = useCallback(() => {
    // A double tap (before the re-render hides the button) undoes once; a committed toast can't undo.
    if (!shown || shown.failed || settled.current.has(shown.key)) return;
    settled.current.add(shown.key);
    const keyAtUndo = nextKey.current;
    show(null);
    const fail = () => {
      // A newer toast (another action's Undo) is up: keep it rather than covering it with this failure.
      if (nextKey.current !== keyAtUndo) return;
      nextKey.current += 1;
      show({ key: nextKey.current, failed: true, message: UNDO_FAILED_MESSAGE });
    };
    try {
      const result = shown.onUndo();
      if (result && typeof (result as Promise<unknown>).then === 'function') {
        (result as Promise<unknown>).catch(fail);
      }
    } catch {
      fail();
    }
  }, [shown, show]);

  const api = useMemo<UndoToastApi>(
    () => ({ showUndo, hideUndo, showError, visible: shown != null }),
    [showUndo, hideUndo, showError, shown],
  );

  return (
    <UndoToastContext.Provider value={api}>
      <View className="flex-1">
        {children}
        {shown ? (
          <Toast
            key={shown.key}
            testID="undo-toast"
            message={shown.message}
            tone={shown.failed ? 'error' : 'info'}
            bottomOffset={bottomOffset}
            action={
              shown.failed
                ? null
                : {
                    label: shown.undoLabel ?? 'Undo',
                    accessibilityLabel: shown.undoAccessibilityLabel,
                    onPress: undo,
                  }
            }
          />
        ) : null}
      </View>
    </UndoToastContext.Provider>
  );
}
