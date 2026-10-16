/** The one 1–10 scale used everywhere, with the words shown beside the number. */
export const SCORE_MIN = 1;
export const SCORE_MAX = 10;

export function clampScore(n: number): number {
  if (!Number.isFinite(n)) return SCORE_MIN;
  return Math.min(SCORE_MAX, Math.max(SCORE_MIN, Math.round(n)));
}

export function scoreLabel(n: number): string {
  const s = clampScore(n);
  if (s <= 2) return "can't stand";
  if (s <= 4) return 'not for me';
  if (s <= 6) return 'fine';
  if (s <= 8) return 'really like';
  return 'all-time';
}
