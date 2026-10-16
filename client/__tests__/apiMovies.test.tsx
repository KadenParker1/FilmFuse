jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { flattenPages, useGenres, useMovie, useMovieSearch, usePopularMovies } from '../api/movies';
import { queryKeys } from '../lib/queryKeys';
import { jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { MovieSummaryDTO, PageDTO } from '../types/api';

const BASE = 'http://localhost:8080/api/v1';
const movie = (id: number, title = `Movie ${id}`): MovieSummaryDTO => ({
  id,
  title,
  releaseYear: 2000 + id,
  posterUrl: null,
  genreIds: [18],
  voteAverage: 7.1,
});
const page = (items: MovieSummaryDTO[], nextCursor: string | null): PageDTO<MovieSummaryDTO> => ({ items, nextCursor });

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const calledUrls = () => fetchMock.mock.calls.map((c) => c[0] as string);

describe('useGenres', () => {
  it('GETs /genres with the bearer token', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [{ id: 28, name: 'Action' }]));
    const { result, client } = await renderHookWithClient(() => useGenres());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([{ id: 28, name: 'Action' }]);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(`${BASE}/genres`);
    expect(init.method).toBeUndefined(); // GET
    expect(init.headers.Authorization).toBe('Bearer tok');
    expect(client.getQueryData(queryKeys.genres)).toHaveLength(1);
  });
});

describe('useMovieSearch', () => {
  it('is disabled for an empty query', async () => {
    const { result } = await renderHookWithClient(() => useMovieSearch('   '));
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('encodes q, pages with the cursor, and stops when nextCursor is null', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, page([movie(1), movie(2)], '2')))
      .mockResolvedValueOnce(jsonResponse(200, page([movie(3)], null)));
    const { result } = await renderHookWithClient(() => useMovieSearch(' star wars & co '));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calledUrls()[0]).toBe(`${BASE}/movies/search?q=star%20wars%20%26%20co`);
    expect(result.current.hasNextPage).toBe(true);

    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(flattenPages(result.current.data).map((m) => m.id)).toEqual([1, 2, 3]));
    expect(calledUrls()[1]).toBe(`${BASE}/movies/search?q=star%20wars%20%26%20co&cursor=2`);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('surfaces 400s as errors', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { message: 'q must be 1-100 characters' }));
    const { result } = await renderHookWithClient(() => useMovieSearch('x'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toBe('q must be 1-100 characters');
  });
});

describe('usePopularMovies', () => {
  it('omits genre when not filtering', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, page([movie(1)], null)));
    const { result } = await renderHookWithClient(() => usePopularMovies());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calledUrls()).toEqual([`${BASE}/movies/popular`]);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('passes genre and cursor', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, page([movie(1)], 'abc')))
      .mockResolvedValueOnce(jsonResponse(200, page([movie(2)], null)));
    const { result, client } = await renderHookWithClient(() => usePopularMovies(27));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(client.getQueryData<{ pages: unknown[] }>(queryKeys.popularMovies(27))?.pages).toHaveLength(2));
    await waitFor(() => expect(result.current.hasNextPage).toBe(false));
    expect(calledUrls()).toEqual([`${BASE}/movies/popular?genre=27`, `${BASE}/movies/popular?genre=27&cursor=abc`]);
  });
});

describe('useMovie', () => {
  it('GETs /movies/{id}', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 550, title: 'Fight Club' }));
    const { result } = await renderHookWithClient(() => useMovie(550));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calledUrls()).toEqual([`${BASE}/movies/550`]);
    expect(result.current.data?.title).toBe('Fight Club');
  });

  it('does not fetch for an invalid id', async () => {
    const { result } = await renderHookWithClient(() => useMovie(NaN));
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports 404 as an ApiError', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Movie not found' }));
    const { result } = await renderHookWithClient(() => useMovie(1));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });
});
