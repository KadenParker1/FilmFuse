import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { countdownSpoken, formatCountdown, isClosing, msUntil, nextTickDelay } from './countdown';

/** After the first "closed" refetch, one more in case the server's clock is a moment behind ours. */
export const EXPIRE_RETRY_MS = 5_000;

export type Countdown = {
  /** Milliseconds left (NaN when `closesAt` is missing or unparseable). */
  msLeft: number;
  /** "Closes in 3h 05m" / "Closes in 12m 05s" / "Closing…" */
  label: string;
  /** "Voting closes in 3 hours 5 minutes" — for accessibilityLabel, never a live region. */
  spoken: string;
  closing: boolean;
};

/**
 * A live countdown to `closesAt`. Ticks once a minute while an hour or more is left and once a second
 * in the last hour (each tick lands just after the shown text changes), and pauses while the app is
 * in the background (it catches up on return). When it reaches zero it calls `onExpire` — and once
 * more {@link EXPIRE_RETRY_MS} later — so the caller can refetch and flip the UI to closed.
 */
export function useCountdown(closesAt: string | null | undefined, onExpire?: () => void): Countdown {
  const [now, setNow] = useState(() => Date.now());
  const onExpireRef = useRef(onExpire);
  useEffect(() => {
    onExpireRef.current = onExpire;
  }, [onExpire]);

  useEffect(() => {
    if (!closesAt || !Number.isFinite(Date.parse(closesAt))) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let expireCalls = 0;
    const stop = () => {
      if (timer !== undefined) clearTimeout(timer);
      timer = undefined;
    };
    const tick = () => {
      const t = Date.now();
      setNow(t);
      const ms = msUntil(closesAt, t);
      if (ms > 0) {
        timer = setTimeout(tick, nextTickDelay(ms));
      } else if (expireCalls < 2) {
        expireCalls += 1;
        onExpireRef.current?.();
        timer = expireCalls < 2 ? setTimeout(tick, EXPIRE_RETRY_MS) : undefined;
      }
    };
    const start = () => {
      stop();
      timer = setTimeout(tick, 0);
    };
    // The jest mock of AppState has no currentState; treat unknown as active.
    if (AppState.currentState !== 'background') start();
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') start();
      else if (state === 'background') stop();
    });
    return () => {
      stop();
      // The jest mock of AppState returns no subscription.
      sub?.remove?.();
    };
  }, [closesAt]);

  const msLeft = msUntil(closesAt, now);
  return { msLeft, label: formatCountdown(msLeft), spoken: countdownSpoken(msLeft), closing: isClosing(msLeft) };
}
