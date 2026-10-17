/** Pending ratings, reports, notification settings and GET /ratings/{id} hooks. */
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { applyLike, invalidateFeedsAfterRating, useLikeRating, useRating } from '../api/feed';
import { useNotificationSettings, useUpdateNotificationSettings } from '../api/notifications';
import { removePendingRating, useDismissPendingRating, usePendingRatings } from '../api/pendingRatings';
import { useUpsertRating } from '../api/ratings';
import { REPORT_DETAILS_MAX, useCreateReport } from '../api/reports';
import { usePickMovie } from '../api/sessions';
import { queryKeys } from '../lib/queryKeys';
import {
  makeFeedItem,
  makeMovie,
  makeNotificationSettings,
  makePendingRating,
  makeRating,
  makeSession,
} from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { FeedItemDTO, NotificationSettingsDTO, PendingRatingDTO } from '../types/api';

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
const bodyOf = (i = 0) => JSON.parse(call(i).init.body as string);

const heat = makePendingRating({ movie: makeMovie({ id: 100, title: 'Heat' }) });
const up = makePendingRating({ movie: makeMovie({ id: 200, title: 'Up' }), groupName: null, sessionId: 's2' });

describe('usePendingRatings', () => {
  it('GETs /me/pending-ratings', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [heat, up]));
    const { result, client } = await renderHookWithClient(() => usePendingRatings());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/pending-ratings`);
    expect(call().init.method).toBeUndefined();
    expect(result.current.data).toEqual([heat, up]);
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([heat, up]);
  });

  it('removePendingRating drops one movie and tolerates an empty cache', () => {
    const client = createTestQueryClient();
    removePendingRating(client, 100);
    expect(client.getQueryData(queryKeys.pendingRatings)).toBeUndefined();
    client.setQueryData(queryKeys.pendingRatings, [heat, up]);
    removePendingRating(client, 100);
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([up]);
  });
});

describe('useDismissPendingRating', () => {
  const seeded = () => {
    const client = createTestQueryClient();
    client.setQueryData<PendingRatingDTO[]>(queryKeys.pendingRatings, [heat, up]);
    return client;
  };

  it('POSTs /me/pending-ratings/{movieId}/dismiss and drops it at once', async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDismissPendingRating(), client);
    await act(async () => {
      result.current.mutate(100);
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(call().url).toBe(`${BASE}/me/pending-ratings/100/dismiss`);
    expect(call().init.method).toBe('POST');
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([up]);
  });

  it('keeps it gone after success', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDismissPendingRating(), client);
    await act(async () => {
      await result.current.mutateAsync(200);
    });
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([heat]);
  });

  it('puts it back when the server refuses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDismissPendingRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync(100)).rejects.toBeDefined();
    });
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([heat, up]);
  });
});

describe('pending ratings stay in step with ratings and picks', () => {
  it('saving a rating (e.g. POST_WATCH) drops that movie and refetches the prompts', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeRating({ movie: makeMovie({ id: 100 }), source: 'POST_WATCH' })));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.pendingRatings, [heat, up]);
    const { result } = await renderHookWithClient(() => useUpsertRating(), client);
    await act(async () => {
      await result.current.mutateAsync({ movieId: 100, body: { score: 8, source: 'POST_WATCH' } });
    });
    expect(bodyOf()).toMatchObject({ score: 8, source: 'POST_WATCH' });
    expect(client.getQueryData(queryKeys.pendingRatings)).toEqual([up]);
    expect(client.getQueryState(queryKeys.pendingRatings)?.isInvalidated).toBe(true);
  });

  it('picking a session movie refetches the prompts', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeSession({ status: 'DECIDED', pickedMovie: makeMovie() })));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.pendingRatings, []);
    const { result } = await renderHookWithClient(() => usePickMovie(), client);
    await act(async () => {
      await result.current.mutateAsync({ sessionId: 's1', movieId: 100 });
    });
    expect(client.getQueryState(queryKeys.pendingRatings)?.isInvalidated).toBe(true);
  });
});

describe('useRating', () => {
  it('GETs /ratings/{id} (encoded) into the detail cache', async () => {
    const item = makeFeedItem({ id: 'r 1', commentCount: 3 });
    fetchMock.mockResolvedValue(jsonResponse(200, item));
    const { result, client } = await renderHookWithClient(() => useRating('r 1'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/ratings/r%201`);
    expect(result.current.data).toEqual(item);
    expect(client.getQueryData(queryKeys.ratingDetail('r 1'))).toEqual(item);
  });

  it.each([undefined, null, ''])('never fetches without an id (%p)', async (id) => {
    await renderHookWithClient(() => useRating(id));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces 404 (deleted / not visible / blocked) as an error', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Not found' }));
    const { result } = await renderHookWithClient(() => useRating('r1'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });

  it('the detail is outside the feed prefix (it holds one item, not pages)', () => {
    expect(queryKeys.ratingDetail('x').slice(0, 1)).not.toEqual(queryKeys.feed);
  });

  it('likes update the cached detail too, and roll back with it', async () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.ratingDetail('f1'), makeFeedItem({ id: 'f1', likeCount: 1, likedByMe: false }));
    applyLike(client, 'f1', true);
    expect(client.getQueryData<FeedItemDTO>(queryKeys.ratingDetail('f1'))).toMatchObject({
      likedByMe: true,
      likeCount: 2,
    });
    applyLike(client, 'f1', false);

    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    const { result } = await renderHookWithClient(() => useLikeRating(), client);
    await act(async () => {
      await expect(result.current.mutateAsync('f1')).rejects.toBeDefined();
    });
    expect(client.getQueryData<FeedItemDTO>(queryKeys.ratingDetail('f1'))).toMatchObject({
      likedByMe: false,
      likeCount: 1,
    });
  });

  it('re-rating refetches cached rating details', () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.ratingDetail('f1'), makeFeedItem());
    invalidateFeedsAfterRating(client, 100);
    expect(client.getQueryState(queryKeys.ratingDetail('f1'))?.isInvalidated).toBe(true);
  });
});

describe('useCreateReport', () => {
  it.each([
    ['RATING', 'f1'],
    ['COMMENT', 'c1'],
    ['USER', 'u2'],
  ] as const)('POSTs /reports for a %s with trimmed details', async (targetType, targetId) => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useCreateReport());
    await act(async () => {
      await result.current.mutateAsync({ targetType, targetId, reason: 'SPAM', details: '  buy now  ' });
    });
    expect(call().url).toBe(`${BASE}/reports`);
    expect(call().init.method).toBe('POST');
    expect(bodyOf()).toEqual({ targetType, targetId, reason: 'SPAM', details: 'buy now' });
  });

  it.each([undefined, null, '', '   '])('sends blank details (%p) as null', async (details) => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useCreateReport());
    await act(async () => {
      await result.current.mutateAsync({ targetType: 'USER', targetId: 'u2', reason: 'OTHER', details });
    });
    expect(bodyOf()).toEqual({ targetType: 'USER', targetId: 'u2', reason: 'OTHER', details: null });
  });

  it('surfaces 404 / 400 failures', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { message: "You can't report yourself" }));
    const { result } = await renderHookWithClient(() => useCreateReport());
    await act(async () => {
      await expect(
        result.current.mutateAsync({ targetType: 'USER', targetId: 'u1', reason: 'SPAM' }),
      ).rejects.toMatchObject({ status: 400, message: "You can't report yourself" });
    });
    expect(REPORT_DETAILS_MAX).toBe(500);
  });
});

describe('notification settings', () => {
  const defaults = makeNotificationSettings();

  it('GETs /me/settings/notifications', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, defaults));
    const { result } = await renderHookWithClient(() => useNotificationSettings());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/settings/notifications`);
    expect(result.current.data).toEqual(defaults);
    expect(defaults.weeklyDigest).toBe(false);
  });

  it('PUTs the whole object, optimistically, then keeps the server answer', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.notificationSettings, defaults);
    const next: NotificationSettingsDTO = { ...defaults, weeklyDigest: true };
    const { result } = await renderHookWithClient(() => useUpdateNotificationSettings(), client);
    await act(async () => {
      result.current.mutate(next);
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(call().url).toBe(`${BASE}/me/settings/notifications`);
    expect(call().init.method).toBe('PUT');
    expect(bodyOf()).toEqual(next);
    expect(client.getQueryData(queryKeys.notificationSettings)).toEqual(next);

    const server = { ...next, comments: false };
    await act(async () => {
      resolve(jsonResponse(200, server));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryData(queryKeys.notificationSettings)).toEqual(server);
  });

  it('rolls back when the save fails', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.notificationSettings, defaults);
    const { result } = await renderHookWithClient(() => useUpdateNotificationSettings(), client);
    await act(async () => {
      await expect(result.current.mutateAsync({ ...defaults, comments: false })).rejects.toBeDefined();
    });
    expect(client.getQueryData(queryKeys.notificationSettings)).toEqual(defaults);
  });
});
