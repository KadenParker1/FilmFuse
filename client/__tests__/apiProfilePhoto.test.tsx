jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { useRemoveProfilePhoto, useUploadProfilePhoto } from '../api/profilePhoto';
import { ApiError } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { ProfileResponseDTO } from '../types/api';

const BASE = 'http://localhost:8080/api/v1';
const UPLOAD_URL = 'https://proj.supabase.co/storage/v1/object/upload/sign/profile-photos/u1/abc.jpg?token=signed';
const profile = (profileImageUrl: string | null): ProfileResponseDTO => ({
  id: 'u1',
  username: 'ada',
  displayName: 'Ada',
  profileImageUrl,
  bio: null,
  status: 'ACTIVE',
  onboardingStep: 0,
  onboardingCompleted: false,
  onboardingCompletedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
});

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

describe('useUploadProfilePhoto', () => {
  const blob = { size: 1234, type: 'image/jpeg' };

  it('requests an upload URL, PUTs the JPEG without auth, confirms, and caches the profile', async () => {
    const updated = profile('https://proj.supabase.co/storage/v1/object/public/profile-photos/u1/abc.jpg');
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { uploadUrl: UPLOAD_URL, path: 'u1/abc.jpg', expiresInSeconds: 7200 }))
      .mockResolvedValueOnce(jsonResponse(200, blob)) // reading the local file
      .mockResolvedValueOnce(jsonResponse(200, {})) // PUT to storage
      .mockResolvedValueOnce(jsonResponse(200, updated));

    const { result, client } = await renderHookWithClient(() => useUploadProfilePhoto());
    client.setQueryData(queryKeys.me, profile(null));
    await act(async () => {
      await result.current.mutateAsync('file:///cache/resized.jpg');
    });

    const [c1, c2, c3, c4] = fetchMock.mock.calls;
    expect(c1[0]).toBe(`${BASE}/me/profile-photo/upload-url`);
    expect(c1[1]).toMatchObject({ method: 'POST', headers: { Authorization: 'Bearer tok' } });

    expect(c2[0]).toBe('file:///cache/resized.jpg');

    expect(c3[0]).toBe(UPLOAD_URL);
    expect(c3[1]).toMatchObject({ method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: blob });
    expect(c3[1].headers).not.toHaveProperty('Authorization');

    expect(c4[0]).toBe(`${BASE}/me/profile-photo`);
    expect(c4[1]).toMatchObject({ method: 'POST', body: JSON.stringify({ path: 'u1/abc.jpg' }) });

    expect(client.getQueryData(queryKeys.me)).toEqual(updated);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it('stops and does not confirm when the storage PUT fails', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { uploadUrl: UPLOAD_URL, path: 'u1/abc.jpg', expiresInSeconds: 7200 }))
      .mockResolvedValueOnce(jsonResponse(200, blob))
      .mockResolvedValueOnce(jsonResponse(403, { message: 'signature verification failed' }));

    const { result, client } = await renderHookWithClient(() => useUploadProfilePhoto());
    client.setQueryData(queryKeys.me, profile(null));
    let err: unknown;
    await act(async () => {
      err = await result.current.mutateAsync('file:///x.jpg').catch((e: unknown) => e);
    });
    expect(err).toBeInstanceOf(ApiError);
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(client.getQueryData<ProfileResponseDTO>(queryKeys.me)?.profileImageUrl).toBeNull();
  });

  it('fails without uploading when the upload URL request fails', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(500, { message: 'storage down' }));
    const { result } = await renderHookWithClient(() => useUploadProfilePhoto());
    await act(async () => {
      await result.current.mutateAsync('file:///x.jpg').catch(() => {});
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('useRemoveProfilePhoto', () => {
  it('DELETEs /me/profile-photo and caches the returned profile', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, profile(null)));
    const { result, client } = await renderHookWithClient(() => useRemoveProfilePhoto());
    client.setQueryData(queryKeys.me, profile('https://x/y.jpg'));
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/me/profile-photo`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'DELETE' });
    expect(client.getQueryData<ProfileResponseDTO>(queryKeys.me)?.profileImageUrl).toBeNull();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
