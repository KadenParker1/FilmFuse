jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { flattenPages } from '../api/movies';
import { useCreateInvite, useRedeemInvite } from '../api/invites';
import {
  useAcceptRequest,
  useBlock,
  useBlocks,
  useCancelRequest,
  useDeclineRequest,
  useFriendRequests,
  useFriends,
  useSendFriendRequest,
  useSetCloseFriend,
  useSuggestions,
  useUnblock,
  useUnfriend,
} from '../api/social';
import {
  applyFriendship,
  filterInfinite,
  mapInfinite,
  type InfinitePages,
} from '../api/socialCache';
import { isSearchableUserQuery, usePublicProfile, useUserSearch } from '../api/users';
import { queryKeys } from '../lib/queryKeys';
import { makeFriend, makePublicProfile, makeRequest, makeSuggestion, makeUser } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type {
  FriendDTO,
  FriendRequestDTO,
  FriendSuggestionDTO,
  PageDTO,
  PublicProfileDTO,
  UserSummaryDTO,
} from '../types/api';

// Deliver query notifications synchronously so cache updates land inside act() (no stray act warnings).
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
const noContent = () => jsonResponse(204, undefined);
const pages = <T,>(...ps: PageDTO<T>[]): InfinitePages<T> => ({
  pages: ps,
  pageParams: ps.map((_, i) => (i ? String(i) : null)),
});
const isInvalidated = (client: ReturnType<typeof createTestQueryClient>, key: readonly unknown[]) =>
  client.getQueryState(key)?.isInvalidated ?? false;

/** Seeds every cache a social mutation may touch, all about user u2. */
function seed(client: ReturnType<typeof createTestQueryClient>, friendship: UserSummaryDTO['friendship'] = 'NONE') {
  client.setQueryData(queryKeys.publicProfile('u2'), makePublicProfile({ friendship, friendCount: 12 }));
  client.setQueryData(
    queryKeys.userSearch('gr'),
    pages({ items: [makeUser({ friendship }), makeUser({ id: 'u3' })], nextCursor: null }),
  );
  client.setQueryData(queryKeys.friendSuggestions(null), [makeSuggestion(), makeSuggestion({ id: 'u3' })]);
  client.setQueryData(queryKeys.friendRequests('INCOMING'), [makeRequest(), makeRequest({ id: 'u4' })]);
  client.setQueryData(queryKeys.friendRequests('OUTGOING'), [
    makeRequest({}, 'OUTGOING'),
    makeRequest({ id: 'u5' }, 'OUTGOING'),
  ]);
  client.setQueryData(queryKeys.friends, pages({ items: [makeFriend(), makeFriend({ id: 'u6' })], nextCursor: null }));
  client.setQueryData(queryKeys.blocks, [
    makeUser({ friendship: 'BLOCKED' }),
    makeUser({ id: 'u7', friendship: 'BLOCKED' }),
  ]);
}
const profile = (client: ReturnType<typeof createTestQueryClient>) =>
  client.getQueryData<PublicProfileDTO>(queryKeys.publicProfile('u2'));
const searchState = (client: ReturnType<typeof createTestQueryClient>, id = 'u2') =>
  flattenPages(client.getQueryData<InfinitePages<UserSummaryDTO>>(queryKeys.userSearch('gr'))).find((u) => u.id === id)
    ?.friendship;
const suggestionIds = (client: ReturnType<typeof createTestQueryClient>) =>
  client.getQueryData<FriendSuggestionDTO[]>(queryKeys.friendSuggestions(null))?.map((s) => s.user.id);
const requestIds = (client: ReturnType<typeof createTestQueryClient>, dir: 'INCOMING' | 'OUTGOING') =>
  client.getQueryData<FriendRequestDTO[]>(queryKeys.friendRequests(dir))?.map((r) => r.user.id);
const friendIds = (client: ReturnType<typeof createTestQueryClient>) =>
  flattenPages(client.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends)).map((f) => f.id);

// ---- queries ----------------------------------------------------------------------------------

describe('useUserSearch', () => {
  it.each([
    ['', false],
    ['a', false],
    ['ab', true],
    ['x'.repeat(50), true],
    ['x'.repeat(51), false],
  ])('isSearchableUserQuery(%p) → %p', (q, ok) => expect(isSearchableUserQuery(q)).toBe(ok));

  it('is disabled below two characters', async () => {
    const { result } = await renderHookWithClient(() => useUserSearch(' a '));
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('GETs /users/search with the encoded, trimmed q and pages with the cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeUser()], nextCursor: 'c1' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeUser({ id: 'u3' })], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useUserSearch(' gr ace& '));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/users/search?q=gr%20ace%26`);
    expect(call(0).init.method).toBeUndefined();
    expect((call(0).init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(flattenPages(result.current.data).map((u) => u.id)).toEqual(['u2', 'u3']));
    expect(call(1).url).toBe(`${BASE}/users/search?q=gr%20ace%26&cursor=c1`);
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('usePublicProfile', () => {
  it('GETs /users/{id}', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makePublicProfile()));
    const { result, client } = await renderHookWithClient(() => usePublicProfile('u2'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/users/u2`);
    expect(client.getQueryData(queryKeys.publicProfile('u2'))).toEqual(makePublicProfile());
  });

  it('does not fetch without an id', async () => {
    const { result } = await renderHookWithClient(() => usePublicProfile(undefined));
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces 404 as an ApiError', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'User not found' }));
    const { result } = await renderHookWithClient(() => usePublicProfile('gone'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });
});

describe('useFriends', () => {
  it('GETs /friends then pages with the cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeFriend()], nextCursor: 'n' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [makeFriend({ id: 'u3' })], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useFriends());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/friends`);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() =>
      expect(flattenPages(client.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends)).map((f) => f.id)).toEqual([
        'u2',
        'u3',
      ]),
    );
    expect(call(1).url).toBe(`${BASE}/friends?cursor=n`);
    expect(client.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends)?.pages[1]?.nextCursor).toBeNull();
  });

  it('can be disabled', async () => {
    const { result } = await renderHookWithClient(() => useFriends({ enabled: false }));
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('list queries', () => {
  it.each([
    ['INCOMING' as const, `${BASE}/friends/requests?direction=INCOMING`],
    ['OUTGOING' as const, `${BASE}/friends/requests?direction=OUTGOING`],
  ])('useFriendRequests(%p) GETs the direction', async (direction, url) => {
    fetchMock.mockResolvedValue(jsonResponse(200, [makeRequest()]));
    const { result, client } = await renderHookWithClient(() => useFriendRequests(direction));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(url);
    expect(client.getQueryData(queryKeys.friendRequests(direction))).toHaveLength(1);
  });

  it('useSuggestions GETs the default list, or passes limit', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [makeSuggestion()]));
    const a = await renderHookWithClient(() => useSuggestions());
    await waitFor(() => expect(a.result.current.isSuccess).toBe(true));
    const b = await renderHookWithClient(() => useSuggestions(25));
    await waitFor(() => expect(b.result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/friends/suggestions`);
    expect(call(1).url).toBe(`${BASE}/friends/suggestions?limit=25`);
  });

  it('useBlocks GETs /blocks', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [makeUser({ friendship: 'BLOCKED' })]));
    const { result } = await renderHookWithClient(() => useBlocks());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/blocks`);
    expect(result.current.data?.[0]?.friendship).toBe('BLOCKED');
  });
});

// ---- mutations --------------------------------------------------------------------------------

describe('useSendFriendRequest', () => {
  it('POSTs the target and marks them requested everywhere', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'u2', friendship: 'REQUEST_SENT' }));
    const client = createTestQueryClient();
    seed(client);
    const { result } = await renderHookWithClient(() => useSendFriendRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/friends/requests`);
    expect(call().init).toMatchObject({ method: 'POST', body: JSON.stringify({ targetUserId: 'u2' }) });
    expect(profile(client)?.friendship).toBe('REQUEST_SENT');
    expect(profile(client)?.friendCount).toBe(12);
    expect(searchState(client)).toBe('REQUEST_SENT');
    expect(searchState(client, 'u3')).toBe('NONE');
    expect(suggestionIds(client)).toEqual(['u3']);
    expect(isInvalidated(client, queryKeys.friendRequests('OUTGOING'))).toBe(true);
    expect(isInvalidated(client, queryKeys.friends)).toBe(false);
  });

  it('handles an auto-accept (they had asked first)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'u2', friendship: 'FRIENDS' }));
    const client = createTestQueryClient();
    seed(client, 'REQUEST_RECEIVED');
    const { result } = await renderHookWithClient(() => useSendFriendRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(profile(client)).toMatchObject({ friendship: 'FRIENDS', friendCount: 13 });
    expect(requestIds(client, 'INCOMING')).toEqual(['u4']);
    expect(isInvalidated(client, queryKeys.friends)).toBe(true);
  });

  it('leaves caches alone on 409', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { message: 'Unblock this person first.' }));
    const client = createTestQueryClient();
    seed(client);
    const { result } = await renderHookWithClient(() => useSendFriendRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2').catch(() => {});
    });
    await waitFor(() => expect(result.current.error?.message).toBe('Unblock this person first.'));
    expect(profile(client)?.friendship).toBe('NONE');
    expect(suggestionIds(client)).toEqual(['u2', 'u3']);
  });
});

describe('useAcceptRequest', () => {
  it('POSTs accept, drops the request and makes them a friend', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'u2', friendship: 'FRIENDS' }));
    const client = createTestQueryClient();
    seed(client, 'REQUEST_RECEIVED');
    const { result } = await renderHookWithClient(() => useAcceptRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/friends/requests/u2/accept`);
    expect(call().init.method).toBe('POST');
    expect(requestIds(client, 'INCOMING')).toEqual(['u4']);
    expect(profile(client)).toMatchObject({ friendship: 'FRIENDS', friendCount: 13 });
    expect(searchState(client)).toBe('FRIENDS');
    expect(isInvalidated(client, queryKeys.friends)).toBe(true);
    expect(isInvalidated(client, queryKeys.friendSuggestions(null))).toBe(true);
  });

  it('encodes the id in the path', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { userId: 'a/b', friendship: 'FRIENDS' }));
    const { result } = await renderHookWithClient(() => useAcceptRequest());
    await act(async () => {
      await result.current.mutateAsync('a/b');
    });
    expect(call().url).toBe(`${BASE}/friends/requests/a%2Fb/accept`);
  });
});

describe('useDeclineRequest', () => {
  it('POSTs decline and resets the state to NONE', async () => {
    fetchMock.mockResolvedValue(noContent());
    const client = createTestQueryClient();
    seed(client, 'REQUEST_RECEIVED');
    const { result } = await renderHookWithClient(() => useDeclineRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/friends/requests/u2/decline`);
    expect(call().init.method).toBe('POST');
    expect(requestIds(client, 'INCOMING')).toEqual(['u4']);
    expect(profile(client)?.friendship).toBe('NONE');
    expect(searchState(client)).toBe('NONE');
    // A stranger stays suggestable.
    expect(suggestionIds(client)).toEqual(['u2', 'u3']);
  });
});

describe('useCancelRequest', () => {
  it('DELETEs the outgoing request', async () => {
    fetchMock.mockResolvedValue(noContent());
    const client = createTestQueryClient();
    seed(client, 'REQUEST_SENT');
    const { result } = await renderHookWithClient(() => useCancelRequest(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/friends/requests/u2`);
    expect(call().init.method).toBe('DELETE');
    expect(requestIds(client, 'OUTGOING')).toEqual(['u5']);
    expect(requestIds(client, 'INCOMING')).toEqual(['u2', 'u4']);
    expect(profile(client)?.friendship).toBe('NONE');
  });
});

describe('useUnfriend', () => {
  it('DELETEs /friends/{id}, removes them and lowers the friend count', async () => {
    fetchMock.mockResolvedValue(noContent());
    const client = createTestQueryClient();
    seed(client, 'FRIENDS');
    const { result } = await renderHookWithClient(() => useUnfriend(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/friends/u2`);
    expect(call().init.method).toBe('DELETE');
    expect(friendIds(client)).toEqual(['u6']);
    expect(profile(client)).toMatchObject({ friendship: 'NONE', friendCount: 11 });
    expect(searchState(client)).toBe('NONE');
    expect(isInvalidated(client, queryKeys.friendSuggestions(null))).toBe(true);
  });
});

describe('useSetCloseFriend', () => {
  it('PATCHes closeFriend, flips the row optimistically and keeps the server copy', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    const client = createTestQueryClient();
    seed(client, 'FRIENDS');
    const { result } = await renderHookWithClient(() => useSetCloseFriend(), client);
    let done!: Promise<unknown>;
    await act(async () => {
      done = result.current.mutateAsync({ userId: 'u2', closeFriend: true });
    });
    const row = () =>
      flattenPages(client.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends)).find((f) => f.id === 'u2');
    await waitFor(() => expect(row()?.closeFriend).toBe(true));
    expect(row()?.closeness).toBe(0.5);
    expect(profile(client)?.closeFriend).toBe(true);
    expect(call().url).toBe(`${BASE}/friends/u2`);
    expect(call().init).toMatchObject({ method: 'PATCH', body: JSON.stringify({ closeFriend: true }) });

    await act(async () => {
      resolve(jsonResponse(200, makeFriend({ closeFriend: true, closeness: 0.8 })));
      await done;
    });
    expect(row()).toMatchObject({ closeFriend: true, closeness: 0.8 });
    expect(profile(client)).toMatchObject({ closeFriend: true, friendship: 'FRIENDS', friendCount: 12 });
    expect(friendIds(client)).toEqual(['u2', 'u6']);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it('rolls back when the server refuses', async () => {
    let reject!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (reject = r)));
    const client = createTestQueryClient();
    seed(client, 'FRIENDS');
    const before = client.getQueryData(queryKeys.friends);
    const profileBefore = profile(client);
    const { result } = await renderHookWithClient(() => useSetCloseFriend(), client);
    let done!: Promise<unknown>;
    await act(async () => {
      done = result.current.mutateAsync({ userId: 'u2', closeFriend: true }).catch(() => {});
    });
    await waitFor(() =>
      expect(flattenPages(client.getQueryData<InfinitePages<FriendDTO>>(queryKeys.friends))[0]?.closeFriend).toBe(true),
    );
    expect(profile(client)?.closeFriend).toBe(true);
    await act(async () => {
      reject(jsonResponse(404, { message: 'Not friends' }));
      await done;
    });
    expect(client.getQueryData(queryKeys.friends)).toEqual(before);
    expect(profile(client)).toEqual(profileBefore);
    expect(profile(client)?.closeFriend).toBe(false);
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it('unstars on the profile alone and restores the star if the server refuses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.publicProfile('u2'), makePublicProfile({ friendship: 'FRIENDS', closeFriend: true }));
    const { result } = await renderHookWithClient(() => useSetCloseFriend(), client);
    await act(async () => {
      await result.current.mutateAsync({ userId: 'u2', closeFriend: false }).catch(() => {});
    });
    expect(profile(client)?.closeFriend).toBe(true);
    expect(client.getQueryData(queryKeys.friends)).toBeUndefined();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it('works when the friends list is not cached', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeFriend({ closeFriend: false })));
    const { result, client } = await renderHookWithClient(() => useSetCloseFriend());
    await act(async () => {
      await result.current.mutateAsync({ userId: 'u2', closeFriend: false });
    });
    expect(client.getQueryData(queryKeys.friends)).toBeUndefined();
    expect(client.getQueryData(queryKeys.publicProfile('u2'))).toBeUndefined();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useBlock', () => {
  it('POSTs /blocks/{id} and scrubs them from friends, requests and suggestions', async () => {
    fetchMock.mockResolvedValue(noContent());
    const client = createTestQueryClient();
    seed(client, 'FRIENDS');
    const { result } = await renderHookWithClient(() => useBlock(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/blocks/u2`);
    expect(call().init.method).toBe('POST');
    expect(friendIds(client)).toEqual(['u6']);
    expect(requestIds(client, 'INCOMING')).toEqual(['u4']);
    expect(requestIds(client, 'OUTGOING')).toEqual(['u5']);
    expect(suggestionIds(client)).toEqual(['u3']);
    expect(profile(client)).toMatchObject({ friendship: 'BLOCKED', friendCount: 11 });
    expect(searchState(client)).toBe('BLOCKED');
    expect(isInvalidated(client, queryKeys.blocks)).toBe(true);
  });
});

describe('useUnblock', () => {
  it('DELETEs /blocks/{id} and removes them from the block list', async () => {
    fetchMock.mockResolvedValue(noContent());
    const client = createTestQueryClient();
    seed(client, 'BLOCKED');
    const { result } = await renderHookWithClient(() => useUnblock(), client);
    await act(async () => {
      await result.current.mutateAsync('u2');
    });
    expect(call().url).toBe(`${BASE}/blocks/u2`);
    expect(call().init.method).toBe('DELETE');
    expect(client.getQueryData<UserSummaryDTO[]>(queryKeys.blocks)?.map((u) => u.id)).toEqual(['u7']);
    expect(profile(client)?.friendship).toBe('NONE');
    expect(isInvalidated(client, queryKeys.friendSuggestions(null))).toBe(true);
  });
});

describe('useCreateInvite', () => {
  it('POSTs /invites and returns the link', async () => {
    const invite = { token: 't0k', url: 'http://localhost:8081/invite/t0k', expiresAt: '2026-10-30T00:00:00Z' };
    fetchMock.mockResolvedValue(jsonResponse(200, invite));
    const { result } = await renderHookWithClient(() => useCreateInvite());
    let out: unknown;
    await act(async () => {
      out = await result.current.mutateAsync();
    });
    expect(call().url).toBe(`${BASE}/invites`);
    expect(call().init.method).toBe('POST');
    expect(out).toEqual(invite);
  });
});

describe('useRedeemInvite', () => {
  it('POSTs the encoded token and records the new friendship', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { inviter: makeUser({ friendship: 'FRIENDS' }) }));
    const client = createTestQueryClient();
    seed(client, 'REQUEST_SENT');
    const { result } = await renderHookWithClient(() => useRedeemInvite(), client);
    await act(async () => {
      await result.current.mutateAsync('ab-_c=');
    });
    expect(call().url).toBe(`${BASE}/invites/ab-_c%3D/redeem`);
    expect(call().init.method).toBe('POST');
    expect(profile(client)).toMatchObject({ friendship: 'FRIENDS', friendCount: 13 });
    expect(requestIds(client, 'INCOMING')).toEqual(['u4']);
    expect(requestIds(client, 'OUTGOING')).toEqual(['u5']);
    expect(isInvalidated(client, queryKeys.friends)).toBe(true);
    expect(isInvalidated(client, queryKeys.friendSuggestions(null))).toBe(true);
  });

  it.each([404, 410, 400])('surfaces %p', async (status) => {
    fetchMock.mockResolvedValue(jsonResponse(status, { message: 'nope' }));
    const { result } = await renderHookWithClient(() => useRedeemInvite());
    await act(async () => {
      await result.current.mutateAsync('x').catch(() => {});
    });
    await waitFor(() => expect(result.current.error).toMatchObject({ status }));
  });
});

// ---- pure cache helpers -----------------------------------------------------------------------

describe('socialCache helpers', () => {
  it('mapInfinite / filterInfinite keep undefined and map every page', () => {
    expect(mapInfinite<number>(undefined, (n) => n)).toBeUndefined();
    expect(filterInfinite<number>(undefined, () => true)).toBeUndefined();
    const data = pages({ items: [1, 2], nextCursor: 'a' }, { items: [3], nextCursor: null });
    expect(mapInfinite(data, (n) => n * 10)?.pages.map((p) => p.items)).toEqual([[10, 20], [30]]);
    expect(filterInfinite(data, (n) => n !== 2)?.pages.map((p) => p.items)).toEqual([[1], [3]]);
    expect(filterInfinite(data, () => true)?.pages[0]?.nextCursor).toBe('a');
  });

  it('applyFriendship never takes a friend count below zero and is a no-op for uncached data', () => {
    const client = createTestQueryClient();
    applyFriendship(client, 'u2', 'FRIENDS');
    expect(client.getQueryData(queryKeys.publicProfile('u2'))).toBeUndefined();
    client.setQueryData(queryKeys.publicProfile('u2'), makePublicProfile({ friendship: 'FRIENDS', friendCount: 0 }));
    applyFriendship(client, 'u2', 'NONE');
    expect(profile(client)?.friendCount).toBe(0);
  });

  it('applyFriendship clears the close-friend flag when a friendship ends', () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.publicProfile('u2'), makePublicProfile({ friendship: 'FRIENDS', closeFriend: true }));
    applyFriendship(client, 'u2', 'FRIENDS');
    expect(profile(client)?.closeFriend).toBe(true);
    applyFriendship(client, 'u2', 'BLOCKED');
    expect(profile(client)).toMatchObject({ friendship: 'BLOCKED', closeFriend: false });
  });
});
