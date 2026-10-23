import { unvotedCount } from '../api/sessions';
import { NAV_TABS, tabAccessibilityLabel } from '../components/nav/navTabs';
import { compareByVotes, computeTally, leaderNote, tallySummary, timeLeft } from '../components/sessions/tally';
import { makeMovie, makeResult, makeSessionSummary } from '../test-utils/fixtures';
import type { SessionResultDTO } from '../types/api';

const r = (id: number, rank: number, up: number, down = 0, vetoed = false): SessionResultDTO =>
  makeResult({ rank, movie: makeMovie({ id, title: `M${id}` }), votes: { up, down, vetoed } });

const ids = (results: SessionResultDTO[]) => results.map((x) => x.movie.id);

describe('compareByVotes', () => {
  it('orders by most up, then fewest down, then ML rank', () => {
    const sorted = [r(1, 1, 1), r(2, 2, 3, 2), r(3, 3, 3, 1), r(4, 4, 3, 1)].sort(compareByVotes);
    expect(ids(sorted)).toEqual([3, 4, 2, 1]);
  });
});

describe('computeTally', () => {
  it('has no leader before anyone gives a thumbs-up', () => {
    const t = computeTally([r(1, 1, 0, 2), r(2, 2, 0)], 3, 1);
    expect(t.leader).toBeNull();
    expect(t.tied).toEqual([]);
    expect(t.isTie).toBe(false);
    expect(tallySummary(t)).toBe('No thumbs-up yet.');
    expect(leaderNote(t)).toBeNull();
  });

  it('picks a clear leader regardless of display order', () => {
    const t = computeTally([r(1, 1, 1), r(2, 2, 2)], 3, 2);
    expect(t.leader?.movie.id).toBe(2);
    expect(t.isTie).toBe(false);
    expect(tallySummary(t)).toBe('Leading: M2, 2 up, 0 down.');
  });

  it('fewest downs breaks equal ups', () => {
    const t = computeTally([r(1, 1, 2, 1), r(2, 2, 2, 0)], 3, 3);
    expect(t.leader?.movie.id).toBe(2);
    expect(t.isTie).toBe(false);
  });

  it('a full tie is explicit and the better ML rank goes first', () => {
    const t = computeTally([r(3, 3, 2, 1), r(1, 1, 2, 1), r(2, 2, 1)], 4, 3);
    expect(t.isTie).toBe(true);
    expect(t.leader?.movie.id).toBe(1);
    expect(ids(t.tied)).toEqual([1, 3]);
    expect(tallySummary(t)).toBe('Tied: M1 and M3, 2 up, 1 down each.');
    expect(leaderNote(t)).toBe('Tied with 1 other — the higher-ranked pick goes first');
    const three = computeTally([r(1, 1, 1), r(2, 2, 1), r(3, 3, 1)], 4, 3);
    expect(tallySummary(three)).toBe('Tied: M1, M2 and M3, 1 up, 0 down each.');
    expect(leaderNote(three)).toBe('Tied with 2 others — the higher-ranked pick goes first');
  });

  it('vetoed results never lead or count as liked by everyone', () => {
    const t = computeTally([r(1, 1, 3, 0, true), r(2, 2, 1)], 3, 3);
    expect(t.leader?.movie.id).toBe(2);
    expect(t.everyoneLikes.has(1)).toBe(false);
  });

  it('marks results every member upvoted and when everyone has voted', () => {
    const t = computeTally([r(1, 1, 3), r(2, 2, 2)], 3, 3);
    expect([...t.everyoneLikes]).toEqual([1]);
    expect(t.everyoneVoted).toBe(true);
    expect(tallySummary(t)).toBe('Leading: M1, 3 up, 0 down. Everyone likes it. Everyone’s voted.');
  });

  it('an empty session has nobody to like or vote', () => {
    const t = computeTally([r(1, 1, 0)], 0, 0);
    expect(t.everyoneLikes.size).toBe(0);
    expect(t.everyoneVoted).toBe(false);
  });
});

describe('timeLeft', () => {
  const now = new Date('2026-10-16T12:00:00Z');
  it.each([
    ['2026-10-17T06:30:00Z', '18h left'],
    ['2026-10-16T12:45:00Z', '45m left'],
    ['2026-10-16T12:00:30Z', 'Closing'],
    ['2026-10-16T11:00:00Z', 'Closing'],
    ['not a date', 'Closing'],
  ])('%s → %s', (closesAt, label) => {
    expect(timeLeft(closesAt, now)).toBe(label);
  });
});

describe('unvotedCount', () => {
  it('counts open sessions the caller has not voted in', () => {
    expect(unvotedCount(undefined)).toBe(0);
    expect(
      unvotedCount([
        makeSessionSummary({ id: 'a', status: 'OPEN', votedByMe: false }),
        makeSessionSummary({ id: 'b', status: 'OPEN', votedByMe: true }),
        makeSessionSummary({ id: 'c', status: 'EXPIRED', votedByMe: false }),
        makeSessionSummary({ id: 'd', status: 'OPEN', votedByMe: false }),
      ]),
    ).toBe(2);
  });
});

describe('tabAccessibilityLabel', () => {
  const tab = (name: string) => NAV_TABS.find((t) => t.name === name)!;
  it('names what the badge counts per tab', () => {
    expect(tabAccessibilityLabel(tab('index'), 0)).toBe('Social');
    expect(tabAccessibilityLabel(tab('index'), 1)).toBe('Social, 1 friend request');
    expect(tabAccessibilityLabel(tab('groups'), 4)).toBe('Groups, 4 waiting on you');
    expect(tabAccessibilityLabel(tab('discover'), 2)).toBe('Discover, 2 new');
  });
});
