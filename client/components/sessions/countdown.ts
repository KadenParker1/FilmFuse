/**
 * Pure helpers for an open session's "Closes in …" countdown (sessions close 24 hours after they
 * start). `useCountdown` ticks them; these stay free of timers so the boundaries are easy to test.
 */

export const SECOND_MS = 1_000;
export const MINUTE_MS = 60_000;
export const HOUR_MS = 3_600_000;

/** Milliseconds until `closesAt`; NaN for a missing or unparseable time. */
export function msUntil(closesAt: string | null | undefined, nowMs: number): number {
  if (!closesAt) return Number.NaN;
  return Date.parse(closesAt) - nowMs;
}

/** True once less than a whole second is left (or the time is unknown). */
export function isClosing(msLeft: number): boolean {
  return !Number.isFinite(msLeft) || msLeft < SECOND_MS;
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/**
 * What's shown: "Closes in 3h 05m" while an hour or more is left, "Closes in 12m 05s" in the last
 * hour, and "Closing…" at zero. Whole units only (floored), so it never claims more time than is left.
 */
export function formatCountdown(msLeft: number): string {
  if (isClosing(msLeft)) return 'Closing…';
  const totalSeconds = Math.floor(msLeft / SECOND_MS);
  if (totalSeconds >= 3600) {
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    return `Closes in ${hours}h ${pad2(minutes)}m`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  return `Closes in ${minutes}m ${pad2(totalSeconds % 60)}s`;
}

const unit = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/**
 * What a screen reader says when the countdown is focused: "Voting closes in 3 hours 12 minutes".
 * Minutes only (no seconds): it's read on focus, never announced as it ticks.
 */
export function countdownSpoken(msLeft: number): string {
  if (isClosing(msLeft)) return 'Voting is closing';
  const totalMinutes = Math.floor(msLeft / MINUTE_MS);
  if (totalMinutes < 1) return 'Voting closes in less than a minute';
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  const parts = [hours ? unit(hours, 'hour') : '', minutes ? unit(minutes, 'minute') : ''].filter(Boolean);
  return `Voting closes in ${parts.join(' ')}`;
}

/**
 * How long until the shown text next changes: the next whole minute while an hour or more is left
 * (so at most a minute between ticks), the next whole second in the last hour, and the moment it
 * reaches zero below one second. +1 ms lands just past the boundary.
 */
export function nextTickDelay(msLeft: number): number {
  if (!Number.isFinite(msLeft) || msLeft <= 0) return 0;
  if (msLeft >= HOUR_MS) return (msLeft % MINUTE_MS) + 1;
  if (msLeft < SECOND_MS) return msLeft + 1;
  return (msLeft % SECOND_MS) + 1;
}
