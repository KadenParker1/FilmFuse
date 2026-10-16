jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { flattenPages } from '../api/movies';
import {
  applyVote,
  useCastVote,
  useClearVote,
  useCreateSession,
  usePickMovie,
  useRefreshSession,
  useSession,
  useSessions,
} from '../api/sessions';
import { queryKeys } from '../lib/queryKeys';
import { makeGroup, makeGroupSummary, makeMovie, makeResult, makeSession, makeSessionSummary } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { SessionDTO } from '../types/api';

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
const body = (i = 0) => JSON.parse(call(i).init.body as string);
type Client = ReturnType<typeof createTestQueryClient>;
const isInvalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;
const cached = (client: Client) => client.getQueryData<SessionDTO>(queryKeys.session('s1'));
const resultFor = (client: Client, movieId: number) => cached(client)?.results.find((r) => r.movie.id === movieId);

function seedHistory(client: Client) {
  client.setQueryData(queryKeys.sessionList(null), { pages: [{ items: [], nextCursor: null }], pageParams: [null] });
  client.setQueryData(queryKeys.sessionList('g1'), { pages: [{ items: [], nextCursor: null }], pageParams: [null] });
  client.setQueryData(queryKeys.groupList, [makeGroupSummary()]);
  client.setQueryData(queryKeys.group('g1'), makeGroup());
  client.setQueryData(queryKeys.group('g2'), makeGroup({ id: 'g2' }));
}

/** A deferred fetch so optimistic state can be inspected mid-flight. */
function deferred() {
  let resolve!: (r: Response) => void;
  const promise = new Promise<Response>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('applyVote', () => {
  const base = makeResult({ votes: { up: 2, down: 1, vetoed: false }, myVote: null });
  it.each([
    [null, 'UP', { up: 3, down: 1, vetoed: false }],
    [null, 'DOWN', { up: 2, down: 2, vetoed: false }],
    [null, 'VETO', { up: 2, down: 1, vetoed: true }],
    ['UP', 'DOWN', { up: 1, down: 2, vetoed: false }],
    ['DOWN', null, { up: 2, down: 0, vetoed: false }],
    ['UP', 'VETO', { up: 1, down: 1, vetoed: true }],
  ] as const)('%p → %p', (from, to, votes) => {
    const r = applyVote({ ...base, myVote: from }, to);
    expect(r.votes).toEqual(votes);
    expect(r.myVote).toBe(to);
  });

  it('keeps vetoed when the caller takes back a veto (others may have vetoed)', () => {
    const r = applyVote(makeResult({ votes: { up: 0, down: 0, vetoed: true }, myVote: 'VETO' }), null);
    expect(r.votes.vetoed).toBe(true);
    expect(r.myVote).toBeNull();
  });

  it('never lets a tally go negative', () => {
    expect(applyVote(makeResult({ votes: { up: 0, down: 0, vetoed: false }, myVote: 'UP' }), null).votes.up).toBe(0);
  });
});

describe('session queries', () => {
  it('useSession GETs /sessions/{id}; disabled without an id', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeSession()));
    const { result } = await renderHookWithClient(() => useSession('s1'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/sessions/s1`);
    const idle = await renderHookWithClient(() => useSession(undefined));
    expect(idle.result.current.fetchStatus).toBe('idle');
  });

  it('useSessions pages through all of the caller’s sessions with the cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeSessionSummary()], nextCursor: 'c1' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeSessionSummary({ id: 's2' })], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useSessions());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/sessions`);
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(flattenPages(result.current.data).map((s) => s.id)).toEqual(['s1', 's2']));
    expect(call(1).url).toBe(`${BASE}/sessions?cursor=c1`);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('useSessions(groupId) filters by group under its own key', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { items: [], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useSessions('g 1'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/sessions?groupId=g%201`);
    expect(client.getQueryData(queryKeys.sessionList('g 1'))).toBeDefined();
  });
});

describe('useCreateSession', () => {
  it('POSTs a group session, caches it and invalidates history + that group', async () => {
    const client = createTestQueryClient();
    seedHistory(client);
    const created = makeSession();
    fetchMock.mockResolvedValue(jsonResponse(201, created));
    const { result } = await renderHookWithClient(() => useCreateSession(), client);
    const dto = { groupId: 'g1', filters: { maxRuntime: 120 }, strategy: 'LEAST_MISERY' as const };
    await act(async () => {
      await result.current.mutateAsync(dto);
    });
    expect(call().url).toBe(`${BASE}/sessions`);
    expect(call().init.method).toBe('POST');
    expect(body()).toEqual(dto);
    expect(cached(client)).toEqual(created);
    expect(isInvalidated(client, queryKeys.sessionList(null))).toBe(true);
    expect(isInvalidated(client, queryKeys.sessionList('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
    expect(isInvalidated(client, queryKeys.group('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.group('g2'))).toBe(false);
  });

  it('POSTs an ad-hoc session with memberIds', async () => {
    const client = createTestQueryClient();
    seedHistory(client);
    fetchMock.mockResolvedValue(jsonResponse(201, makeSession({ groupId: null, groupName: null })));
    const { result } = await renderHookWithClient(() => useCreateSession(), client);
    await act(async () => {
      await result.current.mutateAsync({ memberIds: ['u2'], filters: null, strategy: 'MEAN' });
    });
    expect(body()).toEqual({ memberIds: ['u2'], filters: null, strategy: 'MEAN' });
    expect(isInvalidated(client, queryKeys.group('g1'))).toBe(false);
  });
});

describe('useCastVote', () => {
  it('PUTs the vote and optimistically updates the tally and myVote', async () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.session('s1'), makeSession());
    const d = deferred();
    fetchMock.mockReturnValue(d.promise);
    const { result } = await renderHookWithClient(() => useCastVote(), client);
    await act(async () => {
      result.current.mutate({ sessionId: 's1', movieId: 100, vote: 'UP' });
    });
    await waitFor(() => expect(resultFor(client, 100)?.myVote).toBe('UP'));
    expect(resultFor(client, 100)?.votes.up).toBe(1);
    expect(resultFor(client, 200)?.myVote).toBeNull();
    expect(call().url).toBe(`${BASE}/sessions/s1/votes/100`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual({ vote: 'UP' });

    const server = makeSession({ results: [makeResult({ votes: { up: 3, down: 0, vetoed: false }, myVote: 'UP' })] });
    await act(async () => {
      d.resolve(jsonResponse(200, server));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(cached(client)).toEqual(server);
  });

  it('marks a veto optimistically', async () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.session('s1'), makeSession());
    fetchMock.mockReturnValue(new Promise(() => {}));
    const { result } = await renderHookWithClient(() => useCastVote(), client);
    await act(async () => {
      result.current.mutate({ sessionId: 's1', movieId: 200, vote: 'VETO' });
    });
    await waitFor(() => expect(resultFor(client, 200)?.votes.vetoed).toBe(true));
  });

  it('rolls back when the server refuses', async () => {
    const client = createTestQueryClient();
    const before = makeSession();
    client.setQueryData(queryKeys.session('s1'), before);
    const d = deferred();
    fetchMock.mockReturnValue(d.promise);
    const { result } = await renderHookWithClient(() => useCastVote(), client);
    await act(async () => {
      result.current.mutate({ sessionId: 's1', movieId: 100, vote: 'DOWN' });
    });
    await waitFor(() => expect(resultFor(client, 100)?.votes.down).toBe(1));
    await act(async () => {
      d.resolve(jsonResponse(409, { message: 'Voting has closed' }));
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(cached(client)).toEqual(before);
    expect(result.current.error).toMatchObject({ status: 409, message: 'Voting has closed' });
  });

  it('does nothing optimistic when the session isn’t cached', async () => {
    const client = createTestQueryClient();
    fetchMock.mockResolvedValue(jsonResponse(200, makeSession()));
    const { result } = await renderHookWithClient(() => useCastVote(), client);
    await act(async () => {
      await result.current.mutateAsync({ sessionId: 's1', movieId: 100, vote: 'UP' });
    });
    expect(cached(client)).toEqual(makeSession());
  });
});

describe('useClearVote', () => {
  it('DELETEs the vote with an optimistic clear', async () => {
    const client = createTestQueryClient();
    client.setQueryData(
      queryKeys.session('s1'),
      makeSession({ results: [makeResult({ votes: { up: 2, down: 0, vetoed: false }, myVote: 'UP' })] }),
    );
    const d = deferred();
    fetchMock.mockReturnValue(d.promise);
    const { result } = await renderHookWithClient(() => useClearVote(), client);
    await act(async () => {
      result.current.mutate({ sessionId: 's1', movieId: 100 });
    });
    await waitFor(() => expect(resultFor(client, 100)?.myVote).toBeNull());
    expect(resultFor(client, 100)?.votes.up).toBe(1);
    expect(call().url).toBe(`${BASE}/sessions/s1/votes/100`);
    expect(call().init.method).toBe('DELETE');
    await act(async () => {
      d.resolve(jsonResponse(500, { message: 'boom' }));
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(resultFor(client, 100)?.myVote).toBe('UP');
    expect(resultFor(client, 100)?.votes.up).toBe(2);
  });
});

describe('useRefreshSession / usePickMovie', () => {
  it('refresh POSTs and stores the appended results', async () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.session('s1'), makeSession());
    const more = makeSession({
      results: [...makeSession().results, makeResult({ rank: 3, movie: makeMovie({ id: 300, title: 'Alien' }) })],
    });
    fetchMock.mockResolvedValue(jsonResponse(200, more));
    const { result } = await renderHookWithClient(() => useRefreshSession(), client);
    await act(async () => {
      await result.current.mutateAsync('s1');
    });
    expect(call().url).toBe(`${BASE}/sessions/s1/refresh`);
    expect(call().init.method).toBe('POST');
    expect(cached(client)?.results).toHaveLength(3);
  });

  it('pick POSTs the movie, stores the DECIDED session and invalidates history', async () => {
    const client = createTestQueryClient();
    seedHistory(client);
    client.setQueryData(queryKeys.session('s1'), makeSession());
    const decided = makeSession({ status: 'DECIDED', pickedMovie: makeMovie() });
    fetchMock.mockResolvedValue(jsonResponse(200, decided));
    const { result } = await renderHookWithClient(() => usePickMovie(), client);
    await act(async () => {
      await result.current.mutateAsync({ sessionId: 's1', movieId: 100 });
    });
    expect(call().url).toBe(`${BASE}/sessions/s1/pick`);
    expect(call().init.method).toBe('POST');
    expect(body()).toEqual({ movieId: 100 });
    expect(cached(client)?.status).toBe('DECIDED');
    expect(isInvalidated(client, queryKeys.sessionList('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.group('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });
});
