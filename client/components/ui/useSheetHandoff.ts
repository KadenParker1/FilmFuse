import { useCallback, useEffect, useRef } from 'react';
import { Platform } from 'react-native';

/** If a closing sheet's Modal never reports `onDismiss`, run the hand-off anyway after this long. */
export const SHEET_DISMISS_FALLBACK_MS = 600;

type TimerRef = { current: ReturnType<typeof setTimeout> | null };

function clearTimer(timer: TimerRef) {
  if (timer.current) clearTimeout(timer.current);
  timer.current = null;
}

/**
 * Closing a sheet and then presenting something native from it (the image picker, or another
 * sheet) in the same tick fails on iOS: UIKit can't present over a view controller that is still
 * animating out, so the picker or second sheet silently never appears. `after(fn)` runs `fn` once
 * the closing sheet's Modal has dismissed (pass `onDismiss` to that Sheet), or after a fallback
 * delay. Android and web present fine straight away, so `fn` runs at once there.
 *
 *   const handoff = useSheetHandoff();
 *   <Sheet visible={open} onClose={...} onDismiss={handoff.onDismiss}>
 *   onPress={() => { setOpen(false); handoff.after(() => setConfirmOpen(true)); }}
 */
export function useSheetHandoff(os: string = Platform.OS) {
  const pending = useRef<(() => void) | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const onDismiss = useCallback(() => {
    clearTimer(timer);
    const fn = pending.current;
    pending.current = null;
    fn?.();
  }, []);

  const after = useCallback(
    (fn: () => void) => {
      if (os !== 'ios') {
        fn();
        return;
      }
      pending.current = fn;
      clearTimer(timer);
      timer.current = setTimeout(onDismiss, SHEET_DISMISS_FALLBACK_MS);
    },
    [os, onDismiss],
  );

  // Unmounted while waiting: drop the hand-off (its screen is gone).
  useEffect(
    () => () => {
      clearTimer(timer);
      pending.current = null;
    },
    [],
  );

  return { after, onDismiss };
}
