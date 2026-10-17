jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import {
  COMMENT_MAX,
  applyCommentCount,
  flattenComments,
  isPendingComment,
  useComments,
  useCreateComment,
  useDeleteComment,
} from '../api/comments';
import { queryKeys } from '../lib/queryKeys';
import { makeComment, makeFeedItem, makeProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { CommentDTO, FeedItemDTO, PageDTO } from '../types/api';

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
const pages = <T,>(...p: T[][]) => ({
  pages: p.map((items, i) => ({ items, nextCursor: i < p.length - 1 ? `c${i + 2}` : null })),
  pageParams: p.map((_, i) => (i === 0 ? null : `c${i + 1}`)),
});
type Client = ReturnType<typeof createTestQueryClient>;
const commentsOf = (client: Client, ratingId = 'f1') =>
  flattenComments(client.getQueryData<{ pages: PageDTO<CommentDTO>[] }>(queryKeys.comments(ratingId)));
const countIn = (client: Client, key: readonly unknown[], id = 'f1') =>
  client.getQueryData<{ pages: PageDTO<FeedItemDTO>[] }>(key)?.pages.flatMap((p) => p.items).find((i) => i.id === id)
    ?.commentCount;

const c1 = makeComment({ id: 'c1', body: 'First' });
const c2 = makeComment({ id: 'c2', body: 'Second' });
const rating = makeFeedItem({ id: 'f1', commentCount: 2 });
const other = makeFeedItem({ id: 'f2', commentCount: 7 });

/** The rating cached in two feeds and its detail, with its comments loaded. */
function seeded() {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.me, makeProfile({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }));
  client.setQueryData(queryKeys.feedList('FRIENDS'), pages([rating, other]));
  client.setQueryData(queryKeys.userRatings('u2'), pages([other], [rating]));
  client.setQueryData(queryKeys.ratingDetail('f1'), rating);
  client.setQueryData(queryKeys.comments('f1'), pages([c1, c2]));
  return client;
}
const COUNT_KEYS = [queryKeys.feedList('FRIENDS'), queryKeys.userRatings('u2')];
const expectCount = (client: Client, n: number) => {
  COUNT_KEYS.forEach((k) => expect(countIn(client, k)).toBe(n));
  expect(client.getQueryData<FeedItemDTO>(queryKeys.ratingDetail('f1'))?.commentCount).toBe(n);
  // Other ratings never move.
  expect(countIn(client, queryKeys.feedList('FRIENDS'), 'f2')).toBe(7);
};

describe('useComments', () => {
  it('GETs /ratings/{id}/comments (id encoded) and pages with the cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [c1], nextCursor: 'next 1' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [c2], nextCursor: null }));
    const { result } = await renderHookWithClient(() => useComments('r 1'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/ratings/r%201/comments`);
    expect((call(0).init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(call(1).url).toBe(`${BASE}/ratings/r%201/comments?cursor=next%201`);
    expect(flattenComments(result.current.data)).toEqual([c1, c2]);
  });

  it.each([undefined, null, ''])('never fetches without an id (%p)', async (id) => {
    await renderHookWithClient(() => useComments(id));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces a 404 (rating not visible)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Not found' }));
    const { result } = await renderHookWithClient(() => useComments('f1'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });
});

describe('flattenComments', () => {
  it('keeps order and, for a duplicate, the later copy', () => {
    const appended = makeComment({ id: 'c9', body: 'mine' });
    const data = { pages: [{ items: [c1, appended], nextCursor: 'x' }, { items: [c2, appended], nextCursor: null }] };
    expect(flattenComments(data).map((c) => c.id)).toEqual(['c1', 'c2', 'c9']);
    expect(flattenComments(undefined)).toEqual([]);
  });
});

describe('applyCommentCount', () => {
  it('moves the count in every feed list and the detail, never below zero', () => {
    const client = seeded();
    applyCommentCount(client, 'f1', 1);
    expectCount(client, 3);
    applyCommentCount(client, 'f1', -10);
    expectCount(client, 0);
  });

  it('copes with nothing cached', () => {
    const client = createTestQueryClient();
    applyCommentCount(client, 'f1', 1);
    expect(client.getQueryData(queryKeys.ratingDetail('f1'))).toBeUndefined();
  });
});

describe('useCreateComment', () => {
  it('POSTs the trimmed body to /ratings/{id}/comments', async () => {
    const saved = makeComment({ id: 'c3', body: 'Nice', mine: true, canDelete: true });
    fetchMock.mockResolvedValue(jsonResponse(201, saved));
    const { result } = await renderHookWithClient(() => useCreateComment('f1'), seeded());
    await act(async () => {
      await result.current.mutateAsync('  Nice  ');
    });
    expect(call().url).toBe(`${BASE}/ratings/f1/comments`);
    expect(call().init.method).toBe('POST');
    expect(JSON.parse(call().init.body as string)).toEqual({ body: 'Nice' });
  });

  it('appends a pending comment by you and bumps the count everywhere while in flight', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useCreateComment('f1'), client);
    await act(async () => {
      result.current.mutate(' Loved it ');
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const list = commentsOf(client);
    expect(list.map((c) => c.body)).toEqual(['First', 'Second', 'Loved it']);
    const pending = list[2]!;
    expect(isPendingComment(pending)).toBe(true);
    expect(pending).toMatchObject({ mine: true, canDelete: false, ratingId: 'f1' });
    expect(pending.author).toMatchObject({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' });
    expectCount(client, 3);

    const saved = makeComment({ id: 'c3', body: 'Loved it', mine: true, canDelete: true });
    await act(async () => {
      resolve(jsonResponse(201, saved));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(commentsOf(client)).toEqual([c1, c2, saved]);
    expectCount(client, 3);
  });

  it('appends to the last loaded page', async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const client = seeded();
    client.setQueryData(queryKeys.comments('f1'), pages([c1], [c2]));
    const { result } = await renderHookWithClient(() => useCreateComment('f1'), client);
    await act(async () => {
      result.current.mutate('x');
    });
    const data = client.getQueryData<{ pages: PageDTO<CommentDTO>[] }>(queryKeys.comments('f1'))!;
    expect(data.pages[0]!.items).toEqual([c1]);
    expect(data.pages[1]!.items.map((c) => c.body)).toEqual(['Second', 'x']);
  });

  it('removes the pending comment and restores the count when the server refuses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'Too long', fieldErrors: { body: 'Too long' } }));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useCreateComment('f1'), client);
    await act(async () => {
      await expect(result.current.mutateAsync('x')).rejects.toMatchObject({ status: 400 });
    });
    expect(commentsOf(client)).toEqual([c1, c2]);
    expectCount(client, 2);
  });

  it('works with nothing cached (no comments list, no signed-in profile)', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, c1));
    const { result, client } = await renderHookWithClient(() => useCreateComment('f1'));
    await act(async () => {
      await result.current.mutateAsync('hi');
    });
    expect(client.getQueryData(queryKeys.comments('f1'))).toBeUndefined();
  });

  it('enforces the documented limit constant', () => {
    expect(COMMENT_MAX).toBe(500);
  });
});

describe('useDeleteComment', () => {
  it('DELETEs /comments/{id}, removes it and lowers the count everywhere', async () => {
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDeleteComment('f1'), client);
    await act(async () => {
      await result.current.mutateAsync('c1');
    });
    expect(call().url).toBe(`${BASE}/comments/c1`);
    expect(call().init.method).toBe('DELETE');
    expect(commentsOf(client)).toEqual([c2]);
    expectCount(client, 1);
  });

  it('is optimistic while in flight', async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDeleteComment('f1'), client);
    await act(async () => {
      result.current.mutate('c2');
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(commentsOf(client)).toEqual([c1]);
    expectCount(client, 1);
  });

  it.each([
    ['403 (not yours)', jsonResponse(403, { message: 'Forbidden' })],
    ['500', jsonResponse(500, { message: 'boom' })],
  ])('puts the comment back in place and restores the count on %s', async (_label, response) => {
    fetchMock.mockResolvedValue(response);
    const client = seeded();
    const { result } = await renderHookWithClient(() => useDeleteComment('f1'), client);
    await act(async () => {
      await expect(result.current.mutateAsync('c1')).rejects.toBeDefined();
    });
    expect(commentsOf(client)).toEqual([c1, c2]);
    expectCount(client, 2);
  });
});
