import { act, renderHook } from '@testing-library/react-native';
import { usernameStatusText } from '../components/profile/usernameStatusText';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { queryKeys } from '../lib/queryKeys';

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
