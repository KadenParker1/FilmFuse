jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { flattenPages } from '../api/movies';
import {
  commitRatingRemoval,
  fetchMyRating,
  hideRatingForRemoval,
  isRatingRemovalPending,
  resetPendingRemovalsForTests,
  undoRatingRemoval,
  useDeleteRating,
  useMyRating,
  useMyRatings,
  useUpsertRating,
} from '../api/ratings';
import { ApiError } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { makeMovie, makeRating, makeRecommendation, makeTasteProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { PageDTO, RatingDTO, RecommendationDTO, TasteProfileDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const call = (i = 0) => ({ url: fetchMock.mock.calls[i][0] as string, init: fetchMock.mock.calls[i][1] as RequestInit });
const body = (i = 0) => JSON.parse(call(i).init.body as string);
type Client = ReturnType<typeof createTestQueryClient>;
const invalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;
const pages = <T,>(items: T[]) => ({ pages: [{ items, nextCursor: null }], pageParams: [null] });

const heat = makeRating({ id: 'r1', movie: makeMovie({ id: 100 }), score: 7 });
const alien = makeRating({ id: 'r2', movie: makeMovie({ id: 200, title: 'Alien' }), score: 9 });

function seeded() {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.myRatings, pages([heat, alien]));
  client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'FRESH' }));
  client.setQueryData(
    queryKeys.recommendations,
    pages([makeRecommendation({ movie: makeMovie({ id: 100 }) }), makeRecommendation({ movie: makeMovie({ id: 300 }) })]),
  );
  return client;
}
const listed = (client: Client) =>
  flattenPages(client.getQueryData<{ pages: PageDTO<RatingDTO>[] }>(queryKeys.myRatings));
const recIds = (client: Client) =>
  flattenPages(client.getQueryData<{ pages: PageDTO<RecommendationDTO>[] }>(queryKeys.recommendations)).map(
    (r) => r.movie.id,
  );

describe('useMyRatings', () => {
  it('pages through /me/ratings with the cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [heat], nextCursor: 'c2' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [alien], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useMyRatings());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/me/ratings`);
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(call(1).url).toBe(`${BASE}/me/ratings?cursor=c2`);
    expect(flattenPages(result.current.data)).toEqual([heat, alien]);
    expect(result.current.hasNextPage).toBe(false);
  });
});

describe('useMyRating', () => {
  it('GETs the rating of one movie', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, heat));
    const { result } = await renderHookWithClient(() => useMyRating(100));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/ratings/100`);
    expect(result.current.data).toEqual(heat);
  });

  it('answers null for an unrated movie (404)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Not rated' }));
    const { result } = await renderHookWithClient(() => useMyRating(100));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it('keeps other failures as errors', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    await expect(fetchMyRating(100)).rejects.toBeInstanceOf(ApiError);
  });

  it.each([null, undefined, 0, -1, 1.5])('never fetches for id %p', async (id) => {
    await renderHookWithClient(() => useMyRating(id as number | null));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('can be disabled', async () => {
    await renderHookWithClient(() => useMyRating(100, { enabled: false }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('useUpsertRating', () => {
  it('PUTs /ratings/{movieId} with the body', async () => {
    const saved = makeRating({ score: 9, body: 'Great', visibility: 'PUBLIC' });
    fetchMock.mockResolvedValue(jsonResponse(200, saved));
    const { result } = await renderHookWithClient(() => useUpsertRating(), seeded());
    const dto = { score: 9, body: 'Great', containsSpoilers: false, visibility: 'PUBLIC' as const, source: 'QUICK_RATE' as const };
    await act(async () => {
      await result.current.mutateAsync({ movieId: 100, body: dto });
    });
    expect(call().url).toBe(`${BASE}/ratings/100`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual(dto);
  });

  it('caches the rating, updates the list in place, drops the movie from "For you" and marks taste rebuilding', async () => {
    const client = seeded();
    const updated = { ...heat, score: 10 };
    fetchMock.mockResolvedValue(jsonResponse(200, updated));
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      await result.current.mutateAsync({ movieId: 100, body: { score: 10 } });
    });
    expect(client.getQueryData(queryKeys.myRating(100))).toEqual(updated);
    expect(listed(client).map((r) => r.score)).toEqual([10, 9]);
    expect(invalidated(client, queryKeys.myRatings)).toBe(true);
    expect(recIds(client)).toEqual([300]);
    expect(invalidated(client, queryKeys.recommendations)).toBe(true);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('REBUILDING');
    expect(invalidated(client, queryKeys.tasteProfile)).toBe(true);
  });

  it('leaves every cache alone when the server refuses', async () => {
    const client = seeded();
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'Invalid', fieldErrors: { body: 'too long' } }));
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync({ movieId: 100, body: { score: 5 } })).rejects.toMatchObject({
        fieldErrors: { body: 'too long' },
      });
    });
    expect(client.getQueryData(queryKeys.myRating(100))).toBeUndefined();
    expect(recIds(client)).toEqual([100, 300]);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('FRESH');
  });
});

describe('useDeleteRating', () => {
  it('DELETEs, clears the cached rating, drops it from the list and marks taste rebuilding', async () => {
    const client = seeded();
    client.setQueryData(queryKeys.myRating(100), heat);
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useDeleteRating(), client);
    await act(async () => {
      await result.current.mutateAsync(100);
    });
    expect(call().url).toBe(`${BASE}/ratings/100`);
    expect(call().init.method).toBe('DELETE');
    expect(client.getQueryData(queryKeys.myRating(100))).toBeNull();
    expect(listed(client)).toEqual([alien]);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('REBUILDING');
    expect(invalidated(client, queryKeys.recommendations)).toBe(true);
  });
});

describe('deferred rating removal', () => {
  afterEach(() => resetPendingRemovalsForTests());

  it('commit sends one DELETE; after Undo or a second commit nothing is sent', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, undefined));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.myRating(100), heat);
    hideRatingForRemoval(client, heat);
    expect(isRatingRemovalPending(100)).toBe(true);
    await expect(commitRatingRemoval(client, 100)).resolves.toBe(true);
    await expect(commitRatingRemoval(client, 100)).resolves.toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(call(0).init.method).toBe('DELETE');

    hideRatingForRemoval(client, alien);
    expect(undoRatingRemoval(client, 200)).toBe(true);
    expect(undoRatingRemoval(client, 200)).toBe(false);
    await expect(commitRatingRemoval(client, 200)).resolves.toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('a re-rate waits for a DELETE already on its way, so the PUT lands last', async () => {
    let finishDelete: (r: Response) => void = () => {};
    fetchMock.mockImplementation((_url: string, init: RequestInit) =>
      init.method === 'DELETE'
        ? new Promise<Response>((resolve) => (finishDelete = resolve))
        : Promise.resolve(jsonResponse(200, heat)),
    );
    const client = createTestQueryClient();
    hideRatingForRemoval(client, heat);
    const committing = commitRatingRemoval(client, 100);
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      result.current.mutate({ movieId: 100, body: { score: 8 } });
    });
    expect(fetchMock.mock.calls.map((c) => (c[1] as RequestInit).method)).toEqual(['DELETE']);
    await act(async () => {
      finishDelete(jsonResponse(204, undefined));
      await committing;
    });
    await waitFor(() => expect(fetchMock.mock.calls.map((c) => (c[1] as RequestInit).method)).toEqual(['DELETE', 'PUT']));
  });
});

