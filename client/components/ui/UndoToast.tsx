import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, View } from 'react-native';
import { Toast } from './Toast';

/** How long an undo toast stays up. */
export const UNDO_TOAST_MS = 5_000;
/** How long the "Couldn't undo" follow-up stays up. */
export const UNDO_FAILED_MS = 3_000;
export const UNDO_FAILED_MESSAGE = 'Couldn’t undo that. Try again.';

export type UndoOptions = {
  /** What just happened, e.g. "Removed from For you". */
  message: string;
  /** Reverses it. A rejected promise shows "Couldn't undo". */
  onUndo: () => void | Promise<unknown>;
  /** Button text; "Undo" by default. */
  undoLabel?: string;
  /** What a screen reader says for the button, e.g. "Undo removing Heat". */
  undoAccessibilityLabel?: string;
};

type UndoToastApi = {
  /** Shows the toast (replacing any other) for {@link UNDO_TOAST_MS}. */
  showUndo: (options: UndoOptions) => void;
  /** Hides it without undoing. */
  hideUndo: () => void;
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
 * The app's undo toast: one at a time, floating above the tab bar, with an Undo button. The action
 * has already happened when it shows; Undo reverses it. Goes away after {@link UNDO_TOAST_MS}, on
 * Undo, or when another one replaces it. Mounted once at the root.
 */
export function UndoToastProvider({ children, bottomOffset }: { children: ReactNode; bottomOffset?: number }) {
  const [shown, setShown] = useState<Shown | null>(null);
  const nextKey = useRef(0);

  const hideUndo = useCallback(() => setShown(null), []);
  const showUndo = useCallback((options: UndoOptions) => {
    nextKey.current += 1;
    setShown({ ...options, key: nextKey.current });
    // iOS has no live regions; announce it so VoiceOver users know Undo is there.
    AccessibilityInfo.announceForAccessibility?.(`${options.message}. ${options.undoLabel ?? 'Undo'} available.`);
  }, []);

  useEffect(() => {
    if (!shown) return;
    const t = setTimeout(() => setShown((s) => (s?.key === shown.key ? null : s)), shown.failed ? UNDO_FAILED_MS : UNDO_TOAST_MS);
    return () => clearTimeout(t);
  }, [shown]);

  const undo = useCallback(() => {
    if (!shown || shown.failed) return;
    setShown(null);
    const fail = () => {
      nextKey.current += 1;
      setShown({ key: nextKey.current, failed: true, message: UNDO_FAILED_MESSAGE });
    };
    try {
      const result = shown.onUndo();
      if (result && typeof (result as Promise<unknown>).then === 'function') {
        (result as Promise<unknown>).catch(fail);
      }
    } catch {
      fail();
    }
  }, [shown]);

  const api = useMemo<UndoToastApi>(() => ({ showUndo, hideUndo, visible: shown != null }), [showUndo, hideUndo, shown]);

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
