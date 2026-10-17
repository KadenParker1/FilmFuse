import type { TasteDimensionDTO, VectorChangeReason, VectorStatus } from '../../types/api';

/** "7" / "7.4": learned values carry one decimal, stated ones are whole. */
export function formatScore(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

/** The value to show for a dimension: learned when the vector has one, otherwise stated. */
export const dimensionValue = (d: TasteDimensionDTO): number | null => d.learned ?? d.stated;

/** "You said 4 · your ratings say 7" — the stated vs learned line in the Taste editor. */
export function statedVsLearned(stated: number | null, learned: number | null): string {
  const said = stated != null ? `You said ${formatScore(stated)}` : 'Not set';
  const says = learned != null ? `your ratings say ${formatScore(learned)}` : 'not enough ratings yet';
  return `${said} · ${says}`;
}

export const STATUS_LABELS: Record<VectorStatus, string> = {
  NONE: 'Not built yet',
  FRESH: 'Up to date',
  REBUILDING: 'Updating…',
};

export const REASON_LABELS: Record<VectorChangeReason, string> = {
  ONBOARDING: 'Finished onboarding',
  TASTE_EDIT: 'Edited your taste',
  RATING: 'Rated movies',
  DISMISS: 'Passed on a pick',
  PICK: 'Picked in a session',
};

/** `genre:28` → { kind: 'genre', id: '28' }; anything malformed → null. */
export function parseDimensionKey(key: string): { kind: 'genre' | 'mood'; id: string } | null {
  const i = key.indexOf(':');
  if (i <= 0) return null;
  const kind = key.slice(0, i);
  const id = key.slice(i + 1);
  if ((kind !== 'genre' && kind !== 'mood') || !id) return null;
  return { kind, id };
}

/** How many top genres the persona card can list. */
export const TOP_GENRES_MAX = 5;

/**
 * The line under the top genres. The server lists only genres above neutral (5), so the list may
 * be short or empty while a taste is still forming.
 */
export function topGenresNote(count: number): string | null {
  if (count === 0) return 'No standout genres yet. Rate a few movies you loved and they’ll show up here.';
  if (count < TOP_GENRES_MAX) return 'Only the genres you rate above neutral show up. Rate more to round this out.';
  return null;
}
