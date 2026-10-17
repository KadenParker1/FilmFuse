jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { flattenPages } from '../api/movies';
import { useDismissRecommendation, useRecommendations } from '../api/recommendations';
import { queryKeys } from '../lib/queryKeys';
import { makeMovie, makeRecommendation, makeTasteProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { PageDTO, RecommendationDTO, TasteProfileDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const call = (i = 0) => ({ url: fetchMock.mock.calls[i][0] as string, init: fetchMock.mock.calls[i][1] as RequestInit });
type Client = ReturnType<typeof createTestQueryClient>;

const a = makeRecommendation({ movie: makeMovie({ id: 1, title: 'A' }) });
const b = makeRecommendation({ movie: makeMovie({ id: 2, title: 'B' }) });
const c = makeRecommendation({ movie: makeMovie({ id: 3, title: 'C' }) });

function seeded() {
  const client = createTestQueryClient();
  client.setQueryData(queryKeys.recommendations, {
    pages: [
      { items: [a, b], nextCursor: 'p2' },
      { items: [c], nextCursor: null },
    ],
    pageParams: [null, 'p2'],
  });
  client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'FRESH' }));
  return client;
}
const ids = (client: Client) =>
  flattenPages(client.getQueryData<{ pages: PageDTO<RecommendationDTO>[] }>(queryKeys.recommendations)).map(
    (r) => r.movie.id,
  );

function deferred() {
  let resolve!: (r: Response) => void;
  const promise = new Promise<Response>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('useRecommendations', () => {
  it('pages through /me/recommendations', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { items: [a], nextCursor: 'x' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [b], nextCursor: null }));
    const { result, client } = await renderHookWithClient(() => useRecommendations());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call(0).url).toBe(`${BASE}/me/recommendations`);
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(ids(client)).toEqual([1, 2]));
    expect(call(1).url).toBe(`${BASE}/me/recommendations?cursor=x`);
  });

  it('does nothing while disabled', async () => {
    await renderHookWithClient(() => useRecommendations({ enabled: false }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('useDismissRecommendation', () => {
  it('removes the pick at once, POSTs the dismissal and marks taste rebuilding', async () => {
    const client = seeded();
    const d = deferred();
    fetchMock.mockReturnValueOnce(d.promise);
    const { result } = await renderHookWithClient(() => useDismissRecommendation(), client);
    await act(async () => {
      result.current.mutate(2);
    });
    await waitFor(() => expect(ids(client)).toEqual([1, 3]));
    expect(call().url).toBe(`${BASE}/me/recommendations/2/dismiss`);
    expect(call().init.method).toBe('POST');
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('FRESH');

    await act(async () => {
      d.resolve(jsonResponse(204, null));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(ids(client)).toEqual([1, 3]);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('REBUILDING');
  });

  it('puts the pick back where it was when the server refuses', async () => {
    const client = seeded();
    const d = deferred();
    fetchMock.mockReturnValueOnce(d.promise);
    const { result } = await renderHookWithClient(() => useDismissRecommendation(), client);
    await act(async () => {
      result.current.mutate(1);
    });
    await waitFor(() => expect(ids(client)).toEqual([2, 3]));
    await act(async () => {
      d.resolve(jsonResponse(500, { message: 'boom' }));
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(ids(client)).toEqual([1, 2, 3]);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('FRESH');
  });

  it('copes with nothing cached', async () => {
    const client = createTestQueryClient();
    fetchMock.mockResolvedValue(jsonResponse(204, null));
    const { result } = await renderHookWithClient(() => useDismissRecommendation(), client);
    await act(async () => {
      await result.current.mutateAsync(9);
    });
    expect(client.getQueryData(queryKeys.recommendations)).toBeUndefined();
  });
});
