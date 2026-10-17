import { ApiError } from '../../lib/apiClient';
import { errorMessage } from '../ui';

/** The onboarding flow's steps, 1-based (the server's `step` is the one the user is on; 0 = not started). */
export const ONBOARDING_STEPS = ['You', 'Genres', 'Favourites', 'Calibration', 'Vibes', 'Comfort', 'Friends', 'Your taste'] as const;
export const TOTAL_STEPS = ONBOARDING_STEPS.length;

/** The step to show for a saved server step: 0 (not started) → 1, never past the last. */
export function clampStep(step: number | null | undefined): number {
  if (step == null || !Number.isFinite(step)) return 1;
  return Math.min(TOTAL_STEPS, Math.max(1, Math.floor(step)));
}

export const onboardingPath = (step: number) => `/onboarding/${clampStep(step)}` as const;

/** Every slider starts here; moving off it is what counts as an answer. */
export const NEUTRAL_SCORE = 5;
export const GENRE_MIN_MOVED = 5;
export const GENRE_MIN_HIGH = 3;
export const GENRE_HIGH_SCORE = 8;

export const FAVORITES_MIN = 3;
export const FAVORITES_MAX = 10;

/** Calibration: "Done" after this many rated, or skippable after this many cards seen. */
export const CALIBRATION_MIN_RATED = 10;
export const CALIBRATION_SKIP_AFTER = 20;

const plural = (n: number, one: string, many: string) => `${n} more ${n === 1 ? one : many}`;

/** Step 2's gate: ≥5 sliders moved off 5 and ≥3 at 8+, with the live hint shown under the list. */
export function genreGate(scores: number[]): { moved: number; high: number; ok: boolean; hint: string } {
  const moved = scores.filter((s) => s !== NEUTRAL_SCORE).length;
  const high = scores.filter((s) => s >= GENRE_HIGH_SCORE).length;
  const ok = moved >= GENRE_MIN_MOVED && high >= GENRE_MIN_HIGH;
  if (ok) return { moved, high, ok, hint: 'That’s plenty to go on. Keep tuning if you like.' };
  const parts: string[] = [];
  if (moved < GENRE_MIN_MOVED) parts.push(`move ${plural(GENRE_MIN_MOVED - moved, 'slider', 'sliders')} off 5`);
  if (high < GENRE_MIN_HIGH) parts.push(`put ${plural(GENRE_MIN_HIGH - high, 'genre', 'genres')} at 8 or higher`);
  const text = parts.join(' and ');
  return { moved, high, ok, hint: `${text.charAt(0).toUpperCase()}${text.slice(1)}.` };
}

/** A server error as one line: its field errors when it has them, else the friendly message. */
export function describeError(error: unknown): string | null {
  if (!error) return null;
  if (error instanceof ApiError && Object.keys(error.fieldErrors).length > 0) {
    return Object.values(error.fieldErrors).join(' ');
  }
  return errorMessage(error);
}

/**
 * Which slider values to send: everything already saved plus everything touched this time, in the
 * given (display) order. Untouched, unsaved sliders were never answered.
 */
export function prefsToSend<K>(order: K[], values: Map<K, number>, saved: Set<K>, touched: Set<K>): [K, number][] {
  return order
    .filter((k) => saved.has(k) || touched.has(k))
    .map((k) => [k, values.get(k) ?? NEUTRAL_SCORE] as [K, number]);
}
