jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useGroupsBadgeCount } from '../api/groupsBadge';
import {
  LIVE_PAGE_SIZE,
  LIVE_REFETCH_MS,
  useAddSessionMembers,
  useCastVote,
  useEndSession,
  useJoinSession,
  useLiveSessions,
  useSessions,
} from '../api/sessions';
import { queryKeys } from '../lib/queryKeys';
import { makeGroupSummary, makeSession, makeSessionSummary } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { PageDTO, SessionSummaryDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const call = (i = 0) => ({
  url: fetchMock.mock.calls[i][0] as string,
  init: fetchMock.mock.calls[i][1] as RequestInit,
});
type Client = ReturnType<typeof createTestQueryClient>;
const isInvalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;

function seedLists(client: Client) {
  client.setQueryData(queryKeys.liveSessions, {
    items: [makeSessionSummary({ id: 's1', status: 'OPEN' }), makeSessionSummary({ id: 's2', status: 'OPEN' })],
    nextCursor: null,
  });
  client.setQueryData(queryKeys.sessionList(null, 'CLOSED'), { pages: [], pageParams: [] });
  client.setQueryData(queryKeys.groupList, []);
}

describe('session hub queries', () => {
  it('useLiveSessions asks for one page of open sessions and refreshes every minute', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { items: [makeSessionSummary({ status: 'OPEN' })], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useLiveSessions());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/sessions?status=OPEN&limit=${LIVE_PAGE_SIZE}`);
    expect(client.getQueryCache().find({ queryKey: queryKeys.liveSessions })?.options).toMatchObject({
      refetchInterval: LIVE_REFETCH_MS,
    });
  });

  it('useSessions(null, CLOSED) is the history under its own key', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { items: [], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useSessions(null, 'CLOSED'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/sessions?status=CLOSED`);
    expect(client.getQueryData(queryKeys.sessionList(null, 'CLOSED'))).toBeDefined();
    expect(client.getQueryData(queryKeys.sessionList(null))).toBeUndefined();
  });

  it('the Groups badge adds open sessions without my vote to pending group invites', async () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.liveSessions, {
      items: [
        makeSessionSummary({ id: 'a', status: 'OPEN', votedByMe: false }),
        makeSessionSummary({ id: 'b', status: 'OPEN', votedByMe: true }),
      ],
      nextCursor: null,
    });
    client.setQueryData(queryKeys.groupList, [
      makeGroupSummary({ id: 'g1', memberStatus: 'INVITED' }),
      makeGroupSummary({ id: 'g2', memberStatus: 'INVITED' }),
      makeGroupSummary({ id: 'g3', memberStatus: 'ACTIVE' }),
    ]);
    fetchMock.mockImplementation(() => new Promise(() => {}));
    const { result } = await renderHookWithClient(() => useGroupsBadgeCount(), client);
    expect(result.current).toBe(3);
  });

  it('the Groups badge is 0 before anything loads', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    const { result } = await renderHookWithClient(() => useGroupsBadgeCount());
    expect(result.current).toBe(0);
  });
});

describe('session membership and status mutations', () => {
  it('useAddSessionMembers POSTs the friends, caches the session and marks the lists stale', async () => {
    const client = createTestQueryClient();
    seedLists(client);
    const updated = makeSession({ memberCount: 3 });
    fetchMock.mockResolvedValue(jsonResponse(200, updated));
    const { result } = await renderHookWithClient(() => useAddSessionMembers(), client);
    await act(async () => {
      await result.current.mutateAsync({ sessionId: 's 1', userIds: ['f1', 'f2'] });
    });
    expect(call().url).toBe(`${BASE}/sessions/s%201/members`);
    expect(call().init.method).toBe('POST');
    expect(JSON.parse(call().init.body as string)).toEqual({ userIds: ['f1', 'f2'] });
    expect(client.getQueryData(queryKeys.session('s1'))).toEqual(updated);
    expect(isInvalidated(client, queryKeys.liveSessions)).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('useJoinSession POSTs join and caches the session', async () => {
    const client = createTestQueryClient();
    seedLists(client);
    const joined = makeSession();
    fetchMock.mockResolvedValue(jsonResponse(200, joined));
    const { result } = await renderHookWithClient(() => useJoinSession(), client);
    await act(async () => {
      await result.current.mutateAsync('s1');
    });
    expect(call().url).toBe(`${BASE}/sessions/s1/join`);
    expect(call().init.method).toBe('POST');
    expect(client.getQueryData(queryKeys.session('s1'))).toEqual(joined);
    expect(isInvalidated(client, queryKeys.liveSessions)).toBe(true);
  });

  it('useEndSession POSTs end and drops it from "Live now" at once', async () => {
    const client = createTestQueryClient();
    seedLists(client);
    const ended = makeSession({ status: 'EXPIRED', endedEarly: true });
    fetchMock.mockResolvedValue(jsonResponse(200, ended));
    const { result } = await renderHookWithClient(() => useEndSession(), client);
    await act(async () => {
      await result.current.mutateAsync('s1');
    });
    expect(call().url).toBe(`${BASE}/sessions/s1/end`);
    const live = client.getQueryData<PageDTO<SessionSummaryDTO>>(queryKeys.liveSessions);
    expect(live?.items.map((s) => s.id)).toEqual(['s2']);
    expect(client.getQueryData(queryKeys.session('s1'))).toEqual(ended);
    expect(isInvalidated(client, queryKeys.sessionList(null, 'CLOSED'))).toBe(true);
  });

  it('a vote marks "Live now" stale (votedByMe and counts move)', async () => {
    const client = createTestQueryClient();
    seedLists(client);
    client.setQueryData(queryKeys.session('s1'), makeSession());
    fetchMock.mockResolvedValue(jsonResponse(200, makeSession()));
    const { result } = await renderHookWithClient(() => useCastVote(), client);
    await act(async () => {
      await result.current.mutateAsync({ sessionId: 's1', movieId: 100, vote: 'UP' });
    });
    expect(isInvalidated(client, queryKeys.liveSessions)).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(false);
  });
});
