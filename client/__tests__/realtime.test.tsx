jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);

type StatusCb = (status: string) => void;
type BroadcastCb = (message: { payload?: unknown }) => void;
const mockState: { status?: StatusCb; broadcast?: BroadcastCb } = {};
type MockChannel = { on: jest.Mock; subscribe: jest.Mock };
const mockChannel: MockChannel = {
  on: jest.fn((_type: string, _filter: unknown, cb: BroadcastCb): MockChannel => {
    mockState.broadcast = cb;
    return mockChannel;
  }),
  subscribe: jest.fn((cb: StatusCb): MockChannel => {
    mockState.status = cb;
    return mockChannel;
  }),
};
const mockSupabase = {
  auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) },
  realtime: { setAuth: jest.fn(() => Promise.resolve()) },
  channel: jest.fn(() => mockChannel),
  removeChannel: jest.fn(() => Promise.resolve('ok')),
};
// A getter: imports are hoisted above the consts, so the mock is read lazily at use time.
jest.mock('../lib/supabase', () => ({
  get supabase() {
    return mockSupabase;
  },
}));

import { act, renderHook } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useSessionLive } from '../api/sessionLive';
import { queryKeys } from '../lib/queryKeys';
import {
  POLL_INTERVAL_MS,
  SESSION_UPDATED_EVENT,
  SUBSCRIBE_TIMEOUT_MS,
  sessionTopic,
  subscribeToSession,
} from '../lib/realtime';
import { makeSession } from '../test-utils/fixtures';
import { createTestQueryClient, createWrapper, jsonResponse } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import type { SessionStatus } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
let sessionStatus: SessionStatus = 'OPEN';

/** Lets pending promises (setAuth, fetches) settle. */
const flush = async () => {
  await act(async () => {
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
  });
};
const advance = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
  await flush();
};

beforeEach(() => {
  jest.useFakeTimers();
  routerState.reset();
  sessionStatus = 'OPEN';
  fetchMock.mockReset();
  fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, makeSession({ status: sessionStatus }))));
  global.fetch = fetchMock as unknown as typeof fetch;
  mockState.status = undefined;
  mockState.broadcast = undefined;
  mockChannel.on.mockClear();
  mockChannel.subscribe.mockClear();
  mockSupabase.channel.mockClear();
  mockSupabase.removeChannel.mockClear();
  mockSupabase.realtime.setAuth.mockReset();
  mockSupabase.realtime.setAuth.mockImplementation(() => Promise.resolve());
});

afterEach(() => {
  jest.useRealTimers();
});

describe('subscribeToSession', () => {
  it('authorises, then joins the private session topic and listens for session_updated', async () => {
    const onMode = jest.fn();
    const onUpdate = jest.fn();
    const stop = subscribeToSession('s1', { onMode, onUpdate });
    expect(onMode).toHaveBeenLastCalledWith('connecting');
    await flush();
    expect(mockSupabase.realtime.setAuth).toHaveBeenCalledWith();
    expect(mockSupabase.channel).toHaveBeenCalledWith('session:s1', { config: { private: true } });
    expect(mockChannel.on).toHaveBeenCalledWith('broadcast', { event: SESSION_UPDATED_EVENT }, expect.any(Function));
    mockState.status?.('SUBSCRIBED');
    expect(onMode).toHaveBeenLastCalledWith('live');
    mockState.broadcast?.({ payload: { sessionId: 's1', type: 'vote' } });
    expect(onUpdate).toHaveBeenCalledWith({ sessionId: 's1', type: 'vote' });
    stop();
    expect(mockSupabase.removeChannel).toHaveBeenCalledWith(mockChannel);
    mockState.broadcast?.({ payload: { type: 'vote' } });
    expect(onUpdate).toHaveBeenCalledTimes(1);
  });

  it('still joins if setAuth fails (the server will reject; we fall back)', async () => {
    mockSupabase.realtime.setAuth.mockImplementation(() => Promise.reject(new Error('no session')));
    const onMode = jest.fn();
    subscribeToSession('s1', { onMode, onUpdate: jest.fn() });
    await flush();
    expect(mockSupabase.channel).toHaveBeenCalled();
    mockState.status?.('CHANNEL_ERROR');
    expect(onMode).toHaveBeenLastCalledWith('polling');
  });

  it('falls back to polling when not SUBSCRIBED in time', async () => {
    const onMode = jest.fn();
    subscribeToSession('s1', { onMode, onUpdate: jest.fn() });
    await flush();
    jest.advanceTimersByTime(SUBSCRIBE_TIMEOUT_MS - 1);
    expect(onMode).not.toHaveBeenCalledWith('polling');
    jest.advanceTimersByTime(1);
    expect(onMode).toHaveBeenLastCalledWith('polling');
    // A late join recovers to live.
    mockState.status?.('SUBSCRIBED');
    expect(onMode).toHaveBeenLastCalledWith('live');
  });

  it.each(['CHANNEL_ERROR', 'TIMED_OUT', 'CLOSED'])('%s → polling', async (status) => {
    const onMode = jest.fn();
    subscribeToSession('s1', { onMode, onUpdate: jest.fn() });
    await flush();
    mockState.status?.(status);
    expect(onMode).toHaveBeenLastCalledWith('polling');
  });

  it('unsubscribing before the join never opens a channel', async () => {
    const onMode = jest.fn();
    const stop = subscribeToSession('s1', { onMode, onUpdate: jest.fn() });
    stop();
    await flush();
    jest.advanceTimersByTime(SUBSCRIBE_TIMEOUT_MS);
    expect(mockSupabase.channel).not.toHaveBeenCalled();
    expect(onMode).not.toHaveBeenCalledWith('polling');
  });

  it('names topics session:{id}', () => {
    expect(sessionTopic('abc')).toBe('session:abc');
  });
});

describe('useSessionLive', () => {
  async function mount(sessionId: string | null = 's1') {
    const client = createTestQueryClient();
    const utils = await renderHook((props: { id: string | undefined }) => useSessionLive(props.id), {
      wrapper: createWrapper(client),
      initialProps: { id: sessionId ?? undefined },
    });
    await flush();
    return { ...utils, client };
  }
  const sessionFetches = () => fetchMock.mock.calls.filter((c) => String(c[0]).endsWith('/sessions/s1')).length;

  it('subscribes, shows connecting then live, and unsubscribes on unmount', async () => {
    const { result, unmount } = await mount();
    expect(result.current.mode).toBe('connecting');
    expect(mockSupabase.channel).toHaveBeenCalledWith('session:s1', { config: { private: true } });
    await act(async () => mockState.status?.('SUBSCRIBED'));
    expect(result.current.mode).toBe('live');
    await unmount();
    expect(mockSupabase.removeChannel).toHaveBeenCalledWith(mockChannel);
  });

  it('a session_updated broadcast refetches the session', async () => {
    const { client } = await mount();
    await act(async () => mockState.status?.('SUBSCRIBED'));
    const before = sessionFetches();
    client.setQueryData(queryKeys.sessionList(null), { pages: [], pageParams: [] });
    client.setQueryData(queryKeys.groupList, []);
    await act(async () => mockState.broadcast?.({ payload: { sessionId: 's1', type: 'vote' } }));
    await flush();
    expect(sessionFetches()).toBe(before + 1);
    // Votes move "Live now" counts and the badge, but not groups.
    expect(client.getQueryState(queryKeys.sessionList(null))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.groupList)?.isInvalidated).toBe(false);
  });

  it.each(['ended', 'members'] as const)('a %s broadcast invalidates history and groups', async (type) => {
    const { client } = await mount();
    client.setQueryData(queryKeys.liveSessions, { items: [], nextCursor: null });
    client.setQueryData(queryKeys.groupList, []);
    await act(async () => mockState.broadcast?.({ payload: { sessionId: 's1', type } }));
    expect(client.getQueryState(queryKeys.liveSessions)?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.groupList)?.isInvalidated).toBe(true);
  });

  it('a refresh broadcast leaves the lists alone', async () => {
    const { client } = await mount();
    client.setQueryData(queryKeys.liveSessions, { items: [], nextCursor: null });
    await act(async () => mockState.broadcast?.({ payload: { sessionId: 's1', type: 'refresh' } }));
    expect(client.getQueryState(queryKeys.liveSessions)?.isInvalidated).toBe(false);
  });

  it('a pick broadcast also invalidates history and groups', async () => {
    const { client } = await mount();
    client.setQueryData(queryKeys.sessionList(null), { pages: [], pageParams: [] });
    client.setQueryData(queryKeys.groupList, []);
    await act(async () => mockState.broadcast?.({ payload: { sessionId: 's1', type: 'pick' } }));
    expect(client.getQueryState(queryKeys.sessionList(null))?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.groupList)?.isInvalidated).toBe(true);
  });

  it('does not poll while live', async () => {
    await mount();
    await act(async () => mockState.status?.('SUBSCRIBED'));
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS * 3);
    expect(sessionFetches()).toBe(before);
  });

  it.each(['CHANNEL_ERROR', 'TIMED_OUT', 'CLOSED'])('polls every 5s after %s', async (status) => {
    const { result } = await mount();
    await act(async () => mockState.status?.(status));
    expect(result.current.mode).toBe('polling');
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS);
    expect(sessionFetches()).toBe(before + 1);
    await advance(POLL_INTERVAL_MS);
    expect(sessionFetches()).toBe(before + 2);
  });

  it('polls when the channel never subscribes within the timeout', async () => {
    const { result } = await mount();
    await advance(SUBSCRIBE_TIMEOUT_MS);
    expect(result.current.mode).toBe('polling');
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS);
    expect(sessionFetches()).toBe(before + 1);
  });

  it('stops polling when the screen loses focus, and resumes on refocus', async () => {
    const { rerender } = await mount();
    await act(async () => mockState.status?.('CHANNEL_ERROR'));
    routerState.focused = false;
    await rerender({ id: 's1' });
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS * 3);
    expect(sessionFetches()).toBe(before);
    routerState.focused = true;
    await rerender({ id: 's1' });
    await advance(POLL_INTERVAL_MS);
    expect(sessionFetches()).toBe(before + 1);
  });

  it('stops polling and listening once the session is no longer OPEN', async () => {
    const { client } = await mount();
    await act(async () => mockState.status?.('CHANNEL_ERROR'));
    await act(async () => {
      client.setQueryData(queryKeys.session('s1'), makeSession({ status: 'DECIDED' }));
    });
    sessionStatus = 'DECIDED';
    expect(mockSupabase.removeChannel).toHaveBeenCalled();
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS * 3);
    expect(sessionFetches()).toBe(before);
  });

  it('never subscribes to a session that is already DECIDED or EXPIRED', async () => {
    sessionStatus = 'EXPIRED';
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.session('s1'), makeSession({ status: 'EXPIRED' }));
    await renderHook(() => useSessionLive('s1'), { wrapper: createWrapper(client) });
    await flush();
    expect(mockSupabase.channel).not.toHaveBeenCalled();
  });

  it('stops polling after unmount', async () => {
    const { unmount } = await mount();
    await act(async () => mockState.status?.('TIMED_OUT'));
    await unmount();
    const before = sessionFetches();
    await advance(POLL_INTERVAL_MS * 2);
    expect(sessionFetches()).toBe(before);
  });

  it('does nothing without a session id', async () => {
    await mount(null);
    await advance(SUBSCRIBE_TIMEOUT_MS + POLL_INTERVAL_MS);
    expect(mockSupabase.channel).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
