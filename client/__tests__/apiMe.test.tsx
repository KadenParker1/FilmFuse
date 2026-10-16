jest.mock('../lib/supabase', () => ({
  supabase: {
    auth: {
      getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })),
      signOut: jest.fn(() => Promise.resolve({ error: null })),
    },
  },
}));

import { act, waitFor } from '@testing-library/react-native';
import { useDeleteAccount, useMe, useUpdateMe } from '../api/me';
import { queryKeys } from '../lib/queryKeys';
import { supabase } from '../lib/supabase';
import { jsonResponse, renderHookWithClient } from '../test-utils/render';

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  (supabase.auth.signOut as jest.Mock).mockClear();
  global.fetch = fetchMock as unknown as typeof fetch;
});

describe('useMe', () => {
  it('GETs /me', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 'u1' }));
    const { result } = await renderHookWithClient(() => useMe());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/me`);
  });
});

describe('useUpdateMe', () => {
  it('PATCHes /me and caches the result', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 'u1', bio: 'hi' }));
    const { result, client } = await renderHookWithClient(() => useUpdateMe());
    await act(async () => {
      await result.current.mutateAsync({ bio: 'hi' });
    });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'PATCH', body: JSON.stringify({ bio: 'hi' }) });
    expect(client.getQueryData(queryKeys.me)).toEqual({ id: 'u1', bio: 'hi' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useDeleteAccount', () => {
  it('DELETEs /me, signs out and clears the cache', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error('no body')) });
    const { result, client } = await renderHookWithClient(() => useDeleteAccount());
    client.setQueryData(queryKeys.me, { id: 'u1' });
    client.setQueryData(queryKeys.genres, []);
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/me`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'DELETE' });
    expect(supabase.auth.signOut).toHaveBeenCalledTimes(1);
    expect(client.getQueryData(queryKeys.me)).toBeUndefined();
    expect(client.getQueryCache().getAll()).toHaveLength(0);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it('keeps the session and cache when the server refuses', async () => {
    fetchMock.mockResolvedValue(jsonResponse(500, { message: 'boom' }));
    const { result, client } = await renderHookWithClient(() => useDeleteAccount());
    client.setQueryData(queryKeys.me, { id: 'u1' });
    await act(async () => {
      await result.current.mutateAsync().catch(() => {});
    });
    expect(supabase.auth.signOut).not.toHaveBeenCalled();
    expect(client.getQueryData(queryKeys.me)).toEqual({ id: 'u1' });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});
