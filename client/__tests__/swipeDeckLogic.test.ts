import {
  SWIPE_DISTANCE_RATIO,
  SWIPE_VELOCITY,
  buildDeck,
  certificationText,
  deckProgress,
  newMatches,
  swipeCardLabel,
  swipeDecision,
} from '../components/sessions/swipeDeckLogic';
import { computeTally } from '../components/sessions/tally';
import { makeMovie, makeResult } from '../test-utils/fixtures';
import type { SessionResultDTO } from '../types/api';

const res = (id: number, rank: number, extra: Partial<SessionResultDTO> = {}) =>
  makeResult({ rank, movie: makeMovie({ id, title: `Movie ${id}` }), ...extra });

describe('buildDeck', () => {
  it('keeps ML rank order whatever the input order', () => {
    expect(buildDeck([res(3, 3), res(1, 1), res(2, 2)]).map((r) => r.movie.id)).toEqual([1, 2, 3]);
  });

  it('drops vetoed results and the ones I already voted on', () => {
    const deck = buildDeck([
      res(1, 1, { myVote: 'UP', votes: { up: 1, down: 0, vetoed: false } }),
      res(2, 2, { myVote: 'DOWN', votes: { up: 0, down: 1, vetoed: false } }),
      res(3, 3, { votes: { up: 0, down: 0, vetoed: true } }),
      res(4, 4, { myVote: 'VETO', votes: { up: 0, down: 0, vetoed: true } }),
      res(5, 5, { votes: { up: 2, down: 1, vetoed: false } }),
    ]);
    expect(deck.map((r) => r.movie.id)).toEqual([5]);
  });

  it('is empty for no results', () => {
    expect(buildDeck([])).toEqual([]);
  });
});

describe('swipeDecision', () => {
  const width = 400;
  const distance = width * SWIPE_DISTANCE_RATIO; // 140

  it('past the distance threshold: right is UP, left is DOWN', () => {
    expect(swipeDecision(distance, 0, width)).toBe('UP');
    expect(swipeDecision(300, 0.1, width)).toBe('UP');
    expect(swipeDecision(-distance, 0, width)).toBe('DOWN');
    expect(swipeDecision(-300, -0.1, width)).toBe('DOWN');
  });

  it('a fast flick counts whatever the distance, in its own direction', () => {
    expect(swipeDecision(30, SWIPE_VELOCITY + 0.1, width)).toBe('UP');
    expect(swipeDecision(-30, -(SWIPE_VELOCITY + 0.1), width)).toBe('DOWN');
  });

  it('a short or slow release springs back (null)', () => {
    expect(swipeDecision(distance - 1, 0.2, width)).toBeNull();
    expect(swipeDecision(-(distance - 1), -0.2, width)).toBeNull();
    expect(swipeDecision(0, 0, width)).toBeNull();
    expect(swipeDecision(30, SWIPE_VELOCITY, width)).toBeNull();
  });

  it('a flick against the drag direction does not count', () => {
    expect(swipeDecision(30, -2, width)).toBeNull();
    expect(swipeDecision(-30, 2, width)).toBeNull();
  });
});

describe('newMatches', () => {
  const results = [
    res(1, 1, { votes: { up: 2, down: 0, vetoed: false } }),
    res(2, 2, { votes: { up: 2, down: 0, vetoed: false } }),
    res(3, 3, { votes: { up: 1, down: 0, vetoed: false } }),
    res(4, 4, { votes: { up: 2, down: 0, vetoed: true } }),
  ];
  const tally = computeTally(results, 2, 2);

  it('only movies everyone likes that have not been seen yet', () => {
    expect(newMatches(new Set(), tally)).toEqual([1, 2]);
    expect(newMatches(new Set([1]), tally)).toEqual([2]);
    expect(newMatches(new Set([1, 2]), tally)).toEqual([]);
  });

  it('never a vetoed movie or one short of everyone', () => {
    expect(newMatches(new Set(), tally)).not.toContain(3);
    expect(newMatches(new Set(), tally)).not.toContain(4);
  });
});

describe('labels', () => {
  it('deckProgress is one-based', () => {
    expect(deckProgress(0, 5)).toBe('Card 1 of 5');
    expect(deckProgress(4, 5)).toBe('Card 5 of 5');
  });

  it('certificationText', () => {
    expect(certificationText('PG_13')).toBe('PG-13');
    expect(certificationText('R')).toBe('R');
    expect(certificationText(null)).toBe('Not rated');
  });

  it('swipeCardLabel reads title, year, rating, why and position', () => {
    expect(swipeCardLabel(res(1, 1), 'Card 1 of 3')).toBe(
      'Movie 1, 1995, R. Crime thriller you both rate highly. Card 1 of 3',
    );
  });

  it('swipeCardLabel skips a missing year and explanation and does not double the full stop', () => {
    const r = res(1, 1, { movie: makeMovie({ id: 1, title: 'Up', releaseYear: null }), certification: null, explanation: '' });
    expect(swipeCardLabel(r, 'Card 2 of 2')).toBe('Up, Not rated. Card 2 of 2');
    expect(swipeCardLabel(res(1, 1, { explanation: 'Loved by all.' }), 'Card 1 of 1')).toBe(
      'Movie 1, 1995, R. Loved by all. Card 1 of 1',
    );
  });
});
