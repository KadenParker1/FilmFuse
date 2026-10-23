import { AppState } from 'react-native';
import { act, render, renderHook, screen } from '@testing-library/react-native';
import {
  HOUR_MS,
  MINUTE_MS,
  SECOND_MS,
  countdownSpoken,
  formatCountdown,
  isClosing,
  msUntil,
  nextTickDelay,
} from '../components/sessions/countdown';
import { SessionCountdown } from '../components/sessions/SessionCountdown';
import { EXPIRE_RETRY_MS, useCountdown } from '../components/sessions/useCountdown';

const NOW = new Date('2026-10-16T12:00:00Z');
const at = (ms: number) => new Date(NOW.getTime() + ms).toISOString();

describe('formatCountdown', () => {
  it.each([
    [24 * HOUR_MS, 'Closes in 24h 00m'],
    [23 * HOUR_MS + 59 * MINUTE_MS + 59 * SECOND_MS, 'Closes in 23h 59m'],
    [3 * HOUR_MS + 12 * MINUTE_MS, 'Closes in 3h 12m'],
    [3 * HOUR_MS + 5 * MINUTE_MS + 59 * SECOND_MS, 'Closes in 3h 05m'],
    [HOUR_MS + MINUTE_MS, 'Closes in 1h 01m'],
    [HOUR_MS, 'Closes in 1h 00m'],
    [HOUR_MS - 1, 'Closes in 59m 59s'],
    [12 * MINUTE_MS + 5 * SECOND_MS, 'Closes in 12m 05s'],
    [MINUTE_MS, 'Closes in 1m 00s'],
    [59 * SECOND_MS + 999, 'Closes in 0m 59s'],
    [SECOND_MS, 'Closes in 0m 01s'],
    [999, 'Closing…'],
    [0, 'Closing…'],
    [-5 * MINUTE_MS, 'Closing…'],
    [Number.NaN, 'Closing…'],
  ])('%d ms → %s', (ms, label) => {
    expect(formatCountdown(ms)).toBe(label);
  });
});

describe('countdownSpoken', () => {
  it.each([
    [3 * HOUR_MS + 12 * MINUTE_MS + 40 * SECOND_MS, 'Voting closes in 3 hours 12 minutes'],
    [HOUR_MS, 'Voting closes in 1 hour'],
    [HOUR_MS + MINUTE_MS, 'Voting closes in 1 hour 1 minute'],
    [12 * MINUTE_MS + 5 * SECOND_MS, 'Voting closes in 12 minutes'],
    [MINUTE_MS, 'Voting closes in 1 minute'],
    [30 * SECOND_MS, 'Voting closes in less than a minute'],
    [0, 'Voting is closing'],
    [Number.NaN, 'Voting is closing'],
  ])('%d ms → %s', (ms, label) => {
    expect(countdownSpoken(ms)).toBe(label);
  });
});

describe('nextTickDelay', () => {
  it('waits for the next whole minute while an hour or more is left', () => {
    expect(nextTickDelay(5 * HOUR_MS + 30 * SECOND_MS)).toBe(30 * SECOND_MS + 1);
    expect(nextTickDelay(5 * HOUR_MS)).toBe(1);
    expect(nextTickDelay(HOUR_MS + 59 * SECOND_MS)).toBe(59 * SECOND_MS + 1);
    // Exactly an hour: the very next millisecond switches to seconds.
    expect(nextTickDelay(HOUR_MS)).toBe(1);
  });

  it('never waits more than a minute', () => {
    for (const ms of [24 * HOUR_MS - 1, 3 * HOUR_MS + 12_345, HOUR_MS + 1]) {
      expect(nextTickDelay(ms)).toBeLessThanOrEqual(MINUTE_MS);
      expect(nextTickDelay(ms)).toBeGreaterThan(0);
    }
  });

  it('ticks each second in the last hour, then at zero', () => {
    expect(nextTickDelay(HOUR_MS - 1)).toBe(SECOND_MS);
    expect(nextTickDelay(12 * MINUTE_MS + 250)).toBe(251);
    expect(nextTickDelay(400)).toBe(401);
    expect(nextTickDelay(0)).toBe(0);
    expect(nextTickDelay(Number.NaN)).toBe(0);
  });
});

describe('msUntil / isClosing', () => {
  it('handles missing and bad times', () => {
    expect(msUntil(at(5_000), NOW.getTime())).toBe(5_000);
    expect(msUntil(null, NOW.getTime())).toBeNaN();
    expect(msUntil('not a date', NOW.getTime())).toBeNaN();
    expect(isClosing(Number.NaN)).toBe(true);
    expect(isClosing(SECOND_MS)).toBe(false);
  });
});

describe('useCountdown', () => {
  beforeEach(() => jest.useFakeTimers({ now: NOW }));
  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  it('ticks once a minute while hours are left', async () => {
    const { result } = await renderHook(() => useCountdown(at(2 * HOUR_MS + 30 * SECOND_MS)));
    expect(result.current.label).toBe('Closes in 2h 00m');
    expect(result.current.spoken).toBe('Voting closes in 2 hours');
    await act(() => jest.advanceTimersByTime(30 * SECOND_MS + 1));
    expect(result.current.label).toBe('Closes in 1h 59m');
    // No per-second ticking yet: nothing changes for the next 59 seconds.
    await act(() => jest.advanceTimersByTime(59 * SECOND_MS));
    expect(result.current.label).toBe('Closes in 1h 59m');
    await act(() => jest.advanceTimersByTime(SECOND_MS));
    expect(result.current.label).toBe('Closes in 1h 58m');
  });

  it('switches to seconds in the last hour and calls onExpire at zero, then once more', async () => {
    const onExpire = jest.fn();
    const { result } = await renderHook(() => useCountdown(at(HOUR_MS + 2 * SECOND_MS), onExpire));
    expect(result.current.label).toBe('Closes in 1h 00m');
    await act(() => jest.advanceTimersByTime(3 * SECOND_MS));
    expect(result.current.label).toBe('Closes in 59m 59s');
    await act(() => jest.advanceTimersByTime(SECOND_MS));
    expect(result.current.label).toBe('Closes in 59m 58s');
    await act(() => jest.advanceTimersByTime(HOUR_MS - 2 * SECOND_MS + 1));
    expect(result.current.closing).toBe(true);
    expect(result.current.label).toBe('Closing…');
    expect(onExpire).toHaveBeenCalledTimes(1);
    await act(() => jest.advanceTimersByTime(EXPIRE_RETRY_MS));
    expect(onExpire).toHaveBeenCalledTimes(2);
    await act(() => jest.advanceTimersByTime(10 * EXPIRE_RETRY_MS));
    expect(onExpire).toHaveBeenCalledTimes(2);
  });

  it('an already-closed time expires straight away', async () => {
    const onExpire = jest.fn();
    const { result } = await renderHook(() => useCountdown(at(-MINUTE_MS), onExpire));
    await act(() => jest.advanceTimersByTime(0));
    expect(result.current.label).toBe('Closing…');
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it('uses the latest onExpire', async () => {
    const first = jest.fn();
    const second = jest.fn();
    const { rerender } = await renderHook(({ cb }: { cb: () => void }) => useCountdown(at(2 * SECOND_MS), cb), {
      initialProps: { cb: first },
    });
    await rerender({ cb: second });
    await act(() => jest.advanceTimersByTime(3 * SECOND_MS));
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('does nothing without a valid time', async () => {
    const onExpire = jest.fn();
    const { result } = await renderHook(() => useCountdown('nope', onExpire));
    await act(() => jest.advanceTimersByTime(HOUR_MS));
    expect(result.current.label).toBe('Closing…');
    expect(onExpire).not.toHaveBeenCalled();
  });

  it('pauses in the background and catches up on return', async () => {
    let onChange: ((s: string) => void) | undefined;
    const remove = jest.fn();
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
      onChange = handler as (s: string) => void;
      return { remove } as unknown as ReturnType<typeof AppState.addEventListener>;
    });
    const { result, unmount } = await renderHook(() => useCountdown(at(30 * MINUTE_MS)));
    await act(() => jest.advanceTimersByTime(0));
    expect(result.current.label).toBe('Closes in 30m 00s');
    await act(() => onChange?.('background'));
    await act(() => jest.advanceTimersByTime(10 * MINUTE_MS));
    expect(result.current.label).toBe('Closes in 30m 00s');
    await act(() => onChange?.('active'));
    await act(() => jest.advanceTimersByTime(0));
    expect(result.current.label).toBe('Closes in 20m 00s');
    await unmount();
    expect(remove).toHaveBeenCalled();
  });

  it('stops ticking on unmount', async () => {
    const onExpire = jest.fn();
    let renders = 0;
    const { unmount } = await renderHook(() => {
      renders += 1;
      return useCountdown(at(2 * SECOND_MS), onExpire);
    });
    await act(() => jest.advanceTimersByTime(0));
    await unmount();
    const before = renders;
    await act(() => jest.advanceTimersByTime(HOUR_MS));
    expect(renders).toBe(before);
    expect(onExpire).not.toHaveBeenCalled();
  });

  it('after the retry it goes quiet: no more ticks or refetches while the server still says open', async () => {
    const onExpire = jest.fn();
    let renders = 0;
    const { result, rerender } = await renderHook(
      ({ c }: { c: string }) => {
        renders += 1;
        return useCountdown(c, onExpire);
      },
      { initialProps: { c: at(5 * MINUTE_MS) } },
    );
    // A closesAt change (e.g. a refetch) restarts the one timer rather than adding a second.
    await rerender({ c: at(SECOND_MS) });
    await act(() => jest.advanceTimersByTime(SECOND_MS + 1));
    expect(onExpire).toHaveBeenCalledTimes(1);
    await act(() => jest.advanceTimersByTime(EXPIRE_RETRY_MS));
    expect(onExpire).toHaveBeenCalledTimes(2);
    // The refetch came back with the same closesAt and a fresh onExpire: still nothing more.
    await rerender({ c: at(SECOND_MS) });
    const before = renders;
    await act(() => jest.advanceTimersByTime(HOUR_MS));
    expect(renders).toBe(before);
    expect(onExpire).toHaveBeenCalledTimes(2);
    expect(result.current.label).toBe('Closing…');
  });

  it('ticks at most once a minute with hours left (no per-second re-renders)', async () => {
    let renders = 0;
    await renderHook(() => {
      renders += 1;
      return useCountdown(at(5 * HOUR_MS));
    });
    await act(() => jest.advanceTimersByTime(0));
    const before = renders;
    await act(() => jest.advanceTimersByTime(10 * MINUTE_MS));
    expect(renders - before).toBeLessThanOrEqual(10);
  });

  it('restarts when closesAt changes', async () => {
    const { result, rerender } = await renderHook(({ c }: { c: string }) => useCountdown(c), {
      initialProps: { c: at(5 * MINUTE_MS) },
    });
    expect(result.current.label).toBe('Closes in 5m 00s');
    await rerender({ c: at(3 * HOUR_MS) });
    await act(() => jest.advanceTimersByTime(0));
    expect(result.current.label).toBe('Closes in 3h 00m');
  });
});

describe('SessionCountdown', () => {
  beforeEach(() => jest.useFakeTimers({ now: NOW }));
  afterEach(() => jest.useRealTimers());

  it('is small muted text with a spoken label and no live region', async () => {
    await render(<SessionCountdown closesAt={at(3 * HOUR_MS + 12 * MINUTE_MS)} />);
    const el = screen.getByTestId('session-countdown');
    expect(el).toHaveTextContent('Closes in 3h 12m');
    expect(screen.getByLabelText('Voting closes in 3 hours 12 minutes')).toBe(el);
    expect(el.props.accessibilityLiveRegion).toBe('none');
    expect(el.props['aria-live']).toBe('off');
    expect(el.props.accessibilityRole).toBeUndefined();
  });

  it('calls onExpire at zero', async () => {
    const onExpire = jest.fn();
    await render(<SessionCountdown closesAt={at(2 * SECOND_MS)} onExpire={onExpire} />);
    await act(() => jest.advanceTimersByTime(2 * SECOND_MS + 1));
    expect(screen.getByTestId('session-countdown')).toHaveTextContent('Closing…');
    expect(onExpire).toHaveBeenCalledTimes(1);
  });
});
