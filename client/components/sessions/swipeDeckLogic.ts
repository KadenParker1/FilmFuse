import type { Certification, SessionResultDTO } from '../../types/api';
import { orderResults } from './sessionCopy';
import type { Tally } from './tally';

/**
 * Pure logic for the session swipe deck (no React Native). The deck is fixed: everyone swipes the
 * same ML-ranked results in rank order; swiping never re-ranks it. Each swipe is an ordinary vote.
 */

export type SwipeVote = 'UP' | 'DOWN';

/** Share of the card width a drag must pass to count as a swipe. */
export const SWIPE_DISTANCE_RATIO = 0.35;
/** Release velocity (px/ms, PanResponder's unit) that counts as a flick, whatever the distance. */
export const SWIPE_VELOCITY = 0.5;

/** The cards still to swipe: not vetoed and not voted on by me yet, in ML rank order. */
export function buildDeck(results: SessionResultDTO[]): SessionResultDTO[] {
  return orderResults(results).filter((r) => !r.votes.vetoed && r.myVote == null);
}

/**
 * What a released drag means: right is a thumbs-up, left a thumbs-down, once it passes the distance
 * threshold or is flicked fast enough in that direction. Anything shorter springs back (null).
 */
export function swipeDecision(dx: number, vx: number, width: number): SwipeVote | null {
  const distance = width * SWIPE_DISTANCE_RATIO;
  if (dx > 0 && (dx >= distance || vx > SWIPE_VELOCITY)) return 'UP';
  if (dx < 0 && (-dx >= distance || vx < -SWIPE_VELOCITY)) return 'DOWN';
  return null;
}

/** Movie ids that everyone now likes and that haven't been shown as a match yet. */
export function newMatches(seen: ReadonlySet<number>, tally: Tally): number[] {
  return [...tally.everyoneLikes].filter((id) => !seen.has(id));
}

/** "Card 2 of 5" (index is zero-based). */
export function deckProgress(index: number, total: number): string {
  return `Card ${index + 1} of ${total}`;
}

/** "R" / "PG-13" / "Not rated", for the card's spoken label. */
export function certificationText(certification: Certification | null | undefined): string {
  return certification ? certification.replace('_', '-') : 'Not rated';
}

/** The card as one sentence for screen readers: "Heat, 1995, R. Why it was picked. Card 1 of 5". */
export function swipeCardLabel(result: SessionResultDTO, progress: string): string {
  const head = [result.movie.title, result.movie.releaseYear, certificationText(result.certification)]
    .filter((p) => p != null && p !== '')
    .join(', ');
  const why = result.explanation ? ` ${result.explanation.replace(/\.$/, '')}.` : '';
  return `${head}.${why} ${progress}`;
}
