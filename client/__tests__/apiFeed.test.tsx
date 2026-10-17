jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import {
  applyLike,
  invalidateFeedsAfterRating,
  useFeed,
  useLikeRating,
  useMovieRatings,
  useUnlikeRating,
  useUserRatings,
} from '../api/feed';
import { flattenPages } from '../api/movies';
import { useDeleteRating, useUpsertRating } from '../api/ratings';
import { queryKeys } from '../lib/queryKeys';
import { makeFeedItem, makeMovie, makeProfile, makeRating } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { FeedItemDTO, PageDTO } from '../types/api';

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
const pages = <T,>(...p: T[][]) => ({
  pages: p.map((items, i) => ({ items, nextCursor: i < p.length - 1 ? `c${i + 2}` : null })),
  pageParams: p.map((_, i) => (i === 0 ? null : `c${i + 1}`)),
});
const invalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;
const itemsAt = (client: Client, key: readonly unknown[]) =>
  flattenPages(client.getQueryData<{ pages: PageDTO<FeedItemDTO>[] }>(key));
const find = (client: Client, key: readonly unknown[], id: string) => itemsAt(client, key).find((i) => i.id === id);

const a = makeFeedItem({ id: 'f1', likeCount: 2, likedByMe: false });
const b = makeFeedItem({ id: 'f2', likeCount: 5, likedByMe: true, movie: makeMovie({ id: 200, title: 'Up' }) });

describe('useFeed', () => {
  it.each(['FRIENDS', 'EVERYONE'] as const)('GETs /feed?scope=%s and pages with the cursor', async (scope) => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [a], nextCursor: 'next 1' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [b], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useFeed(scope));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/feed?scope=${scope}`);
    expect(call(0).init.method).toBeUndefined();
    expect((call(0).init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(call(1).url).toBe(`${BASE}/feed?scope=${scope}&cursor=next%201`);
    expect(flattenPages(result.current.data)).toEqual([a, b]);
    expect(result.current.hasNextPage).toBe(false);
    expect(client.getQueryData(queryKeys.feedList(scope))).toBeDefined();
  });

  it('keeps the two scopes in separate caches', () => {
    expect(queryKeys.feedList('FRIENDS')).not.toEqual(queryKeys.feedList('EVERYONE'));
    expect(queryKeys.feedList('FRIENDS').slice(0, 1)).toEqual(queryKeys.feed);
  });
});

describe('useMovieRatings', () => {
  it.each(['FRIENDS', 'EVERYONE'] as const)('GETs /movies/{id}/ratings?scope=%s and pages', async (scope) => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [a], nextCursor: 'c2' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [b], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useMovieRatings(100, scope));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/movies/100/ratings?scope=${scope}`);
    expect(flattenPages(result.current.data)).toEqual([a]);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(call(1).url).toBe(`${BASE}/movies/100/ratings?scope=${scope}&cursor=c2`);
    await waitFor(() => expect(flattenPages(result.current.data)).toEqual([a, b]));
  });

  it.each([null, undefined, 0, -3, 1.5])('never fetches for movie id %p', async (id) => {
    await renderHookWithClient(() => useMovieRatings(id as number | null, 'FRIENDS'));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('useUserRatings', () => {
  it('GETs /users/{id}/ratings (id encoded) and pages', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [a], nextCursor: 'c2' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useUserRatings('u 2'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/users/u%202/ratings`);
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(call(1).url).toBe(`${BASE}/users/u%202/ratings?cursor=c2`);
    await waitFor(() => expect(result.current.hasNextPage).toBe(false));
  });

  it.each([undefined, null, ''])('never fetches without an id (%p)', async (id) => {
    await renderHookWithClient(() => useUserRatings(id));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces a 404 (blocked / unknown) as an error', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Not found' }));
    const { result } = await renderHookWithClient(() => useUserRatings('u2'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });
});

/** The same rating cached in every kind of list, on a second page in one of them. */
function seeded() {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.feedList('FRIENDS'), pages([a, b]));
  client.setQueryData(queryKeys.feedList('EVERYONE'), pages([b], [a]));
  client.setQueryData(queryKeys.movieRatings(100, 'FRIENDS'), pages([a]));
  client.setQueryData(queryKeys.userRatings('u2'), pages([b, a]));
  // Not a feed list: must never be touched.
  client.setQueryData(queryKeys.myRatings, pages([makeRating()]));
  return client;
}
const LISTS = [
  queryKeys.feedList('FRIENDS'),
  queryKeys.feedList('EVERYONE'),
  queryKeys.movieRatings(100, 'FRIENDS'),
  queryKeys.userRatings('u2'),
];

describe('applyLike', () => {
  it('flips the flag and count only where it changes, in every feed list', () => {
    const client = seeded();
    applyLike(client, 'f1', true);
    LISTS.forEach((key) => expect(find(client, key, 'f1')).toMatchObject({ likedByMe: true, likeCount: 3 }));
    applyLike(client, 'f1', true);
    LISTS.forEach((key) => expect(find(client, key, 'f1')?.likeCount).toBe(3));
    expect(find(client, queryKeys.feedList('FRIENDS'), 'f2')).toEqual(b);
  });

  it('never drops below zero', () => {
    const client = createTestQueryClient();
    client.setQueryData(
      queryKeys.feedList('FRIENDS'),
      pages([makeFeedItem({ id: 'z', likeCount: 0, likedByMe: true })]),
    );
    applyLike(client, 'z', false);
    expect(find(client, queryKeys.feedList('FRIENDS'), 'z')).toMatchObject({ likedByMe: false, likeCount: 0 });
  });
});

describe('useLikeRating', () => {
  it('POSTs /ratings/{id}/like', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useLikeRating(), seeded());
    await act(async () => {
      await result.current.mutateAsync('f1');
    });
    expect(call().url).toBe(`${BASE}/ratings/f1/like`);
    expect(call().init.method).toBe('POST');
  });

  it('is optimistic across every cached list while in flight and keeps it after success', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    const client = seeded();
    const before = client.getQueryData(queryKeys.myRatings);
    const { result } = await renderHookWithClient(() => useLikeRating(), client);
    await act(async () => {
      result.current.mutate('f1');
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    LISTS.forEach((key) => expect(find(client, key, 'f1')).toMatchObject({ likedByMe: true, likeCount: 3 }));
    expect(find(client, queryKeys.userRatings('u2'), 'f2')).toEqual(b);
    expect(client.getQueryData(queryKeys.myRatings)).toBe(before);

    await act(async () => {
      resolve(jsonResponse(204, null));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    LISTS.forEach((key) => expect(find(client, key, 'f1')).toMatchObject({ likedByMe: true, likeCount: 3 }));
  });

  it('rolls every list back when the server refuses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Rating not found' }));
    const client = seeded();
    const snapshot = LISTS.map((key) => client.getQueryData(key));
    const { result } = await renderHookWithClient(() => useLikeRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync('f1')).rejects.toMatchObject({ status: 404 });
    });
    LISTS.forEach((key, i) => expect(client.getQueryData(key)).toEqual(snapshot[i]));
    LISTS.forEach((key) => expect(find(client, key, 'f1')).toMatchObject({ likedByMe: false, likeCount: 2 }));
  });

  it('works with nothing cached', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result, client } = await renderHookWithClient(() => useLikeRating());
    await act(async () => {
      await result.current.mutateAsync('f1');
    });
    expect(client.getQueryData(queryKeys.feedList('FRIENDS'))).toBeUndefined();
  });
});

describe('useUnlikeRating', () => {
  it('DELETEs /ratings/{id}/like and decrements everywhere', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useUnlikeRating(), client);
    await act(async () => {
      await result.current.mutateAsync('f2');
    });
    expect(call().url).toBe(`${BASE}/ratings/f2/like`);
    expect(call().init.method).toBe('DELETE');
    [queryKeys.feedList('FRIENDS'), queryKeys.feedList('EVERYONE'), queryKeys.userRatings('u2')].forEach((key) =>
      expect(find(client, key, 'f2')).toMatchObject({ likedByMe: false, likeCount: 4 }),
    );
  });

  it('rolls back on a network failure', async () => {
    fetchMock.mockRejectedValue(new TypeError('offline'));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useUnlikeRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync('f2')).rejects.toBeDefined();
    });
    expect(find(client, queryKeys.feedList('EVERYONE'), 'f2')).toMatchObject({ likedByMe: true, likeCount: 5 });
  });
});

describe('feed invalidation after a rating changes', () => {
  function cached() {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.me, makeProfile({ id: 'u1' }));
    const keys = {
      friends: queryKeys.feedList('FRIENDS'),
      everyone: queryKeys.feedList('EVERYONE'),
      movieFriends: queryKeys.movieRatings(100, 'FRIENDS'),
      movieEveryone: queryKeys.movieRatings(100, 'EVERYONE'),
      otherMovie: queryKeys.movieRatings(200, 'FRIENDS'),
      mine: queryKeys.userRatings('u1'),
      someoneElse: queryKeys.userRatings('u2'),
    };
    Object.values(keys).forEach((k) => client.setQueryData(k, pages([a])));
    return { client, keys };
  }

  it('invalidateFeedsAfterRating marks the feeds, that movie and your own ratings stale — nothing else', () => {
    const { client, keys } = cached();
    invalidateFeedsAfterRating(client, 100);
    expect(invalidated(client, keys.friends)).toBe(true);
    expect(invalidated(client, keys.everyone)).toBe(true);
    expect(invalidated(client, keys.movieFriends)).toBe(true);
    expect(invalidated(client, keys.movieEveryone)).toBe(true);
    expect(invalidated(client, keys.mine)).toBe(true);
    expect(invalidated(client, keys.otherMovie)).toBe(false);
    expect(invalidated(client, keys.someoneElse)).toBe(false);
  });

  it('skips your own list when /me is not cached', () => {
    const { client, keys } = cached();
    client.removeQueries({ queryKey: queryKeys.me });
    invalidateFeedsAfterRating(client, 100);
    expect(invalidated(client, keys.friends)).toBe(true);
    expect(invalidated(client, keys.mine)).toBe(false);
  });

  it('a quick-rate save invalidates them', async () => {
    const { client, keys } = cached();
    fetchMock.mockResolvedValue(jsonResponse(200, makeRating({ movie: makeMovie({ id: 100 }) })));
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      await result.current.mutateAsync({ movieId: 100, body: { score: 9 } });
    });
    expect(invalidated(client, keys.friends)).toBe(true);
    expect(invalidated(client, keys.movieFriends)).toBe(true);
    expect(invalidated(client, keys.movieEveryone)).toBe(true);
    expect(invalidated(client, keys.mine)).toBe(true);
    expect(invalidated(client, keys.otherMovie)).toBe(false);
  });

  it('a quick-rate delete invalidates them', async () => {
    const { client, keys } = cached();
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useDeleteRating(), client);
    await act(async () => {
      await result.current.mutateAsync(100);
    });
    expect(invalidated(client, keys.friends)).toBe(true);
    expect(invalidated(client, keys.movieFriends)).toBe(true);
    expect(invalidated(client, keys.mine)).toBe(true);
    expect(invalidated(client, keys.someoneElse)).toBe(false);
  });

  it('a refused save leaves them alone', async () => {
    const { client, keys } = cached();
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'Invalid' }));
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync({ movieId: 100, body: { score: 9 } })).rejects.toBeDefined();
    });
    expect(invalidated(client, keys.friends)).toBe(false);
    expect(invalidated(client, keys.mine)).toBe(false);
  });
});
