import { act, renderHook } from '@testing-library/react-native';
import { usernameStatusText } from '../components/profile/usernameStatusText';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { queryKeys } from '../lib/queryKeys';
import { badgeCount, personName, pluralize } from '../lib/format';
import { withQuery } from '../lib/withQuery';

describe('useDebouncedValue', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('only settles after the delay since the last change', async () => {
    const { result, rerender } = await renderHook(({ v }: { v: string }) => useDebouncedValue(v, 300), {
      initialProps: { v: 'a' },
    });
    expect(result.current).toBe('a');
    await rerender({ v: 'ab' });
    await act(async () => {
      jest.advanceTimersByTime(200);
    });
    await rerender({ v: 'abc' });
    await act(async () => {
      jest.advanceTimersByTime(200);
    });
    expect(result.current).toBe('a');
    await act(async () => {
      jest.advanceTimersByTime(100);
    });
    expect(result.current).toBe('abc');
  });
});

describe('usernameStatusText', () => {
  it.each([
    ['idle', null],
    ['checking', { text: 'Checking…', tone: 'muted' }],
    ['available', { text: 'Available', tone: 'good' }],
    ['taken', { text: 'Already taken', tone: 'bad' }],
    ['invalid', { text: expect.stringContaining('3–30'), tone: 'bad' }],
    ['error', { text: expect.stringContaining("Couldn't"), tone: 'muted' }],
    ['unchanged', { text: 'Your current username', tone: 'muted' }],
  ] as const)('%p', (status, expected) => {
    expect(usernameStatusText(status)).toEqual(expected);
  });
});

describe('queryKeys', () => {
  it('nests movie keys under the movies prefix', () => {
    expect(queryKeys.movieSearch('dune').slice(0, 1)).toEqual(queryKeys.movies);
    expect(queryKeys.popularMovies(null).slice(0, 1)).toEqual(queryKeys.movies);
    expect(queryKeys.movie(1).slice(0, 1)).toEqual(queryKeys.movies);
    expect(queryKeys.popularMovies(28)).not.toEqual(queryKeys.popularMovies(null));
  });
});

describe('social query keys', () => {
  it('nest under their prefixes so invalidation reaches them', () => {
    expect(queryKeys.userSearch('gr').slice(0, 2)).toEqual(queryKeys.userSearchAll);
    expect(queryKeys.friendRequests('INCOMING').slice(0, 2)).toEqual(queryKeys.friendRequestsAll);
    expect(queryKeys.friendSuggestions(null).slice(0, 2)).toEqual(queryKeys.friendSuggestionsAll);
    for (const key of [queryKeys.publicProfile('u'), queryKeys.friends, queryKeys.blocks, queryKeys.userSearch('x')]) {
      expect(key[0]).toBe(queryKeys.social[0]);
    }
    expect(queryKeys.publicProfile('search')).not.toEqual(queryKeys.userSearchAll);
  });
});

describe('format helpers', () => {
  it('pluralize', () => {
    expect(pluralize(0, 'friend')).toBe('0 friends');
    expect(pluralize(1, 'friend')).toBe('1 friend');
    expect(pluralize(2, 'person', 'people')).toBe('2 people');
  });

  it.each([
    [0, undefined],
    [null, undefined],
    [-1, undefined],
    [NaN, undefined],
    [3, 3],
    [9, 9],
    [10, '9+'],
  ])('badgeCount(%p) → %p', (n, out) => expect(badgeCount(n)).toBe(out));

  it('personName prefers the display name, then @username', () => {
    expect(personName({ displayName: 'Ada', username: 'ada' })).toBe('Ada');
    expect(personName({ displayName: '  ', username: 'ada' })).toBe('@ada');
    expect(personName({ displayName: null, username: null })).toBe('New member');
  });
});

describe('withQuery', () => {
  it('encodes values and drops empty ones', () => {
    expect(withQuery('/p', {})).toBe('/p');
    expect(withQuery('/p', { a: 'x y', b: null, c: undefined, d: '', e: 0 })).toBe('/p?a=x%20y&e=0');
  });
});
