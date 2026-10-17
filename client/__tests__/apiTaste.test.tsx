jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { markTasteRebuilding, shouldPollTaste, TASTE_POLL_MS, useTasteHistory, useTasteProfile } from '../api/taste';
import { queryKeys } from '../lib/queryKeys';
import { makeHistoryEntry, makeTasteProfile } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { TasteProfileDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
afterEach(() => jest.useRealTimers());

const urls = () => fetchMock.mock.calls.map((c) => c[0] as string);
/** Lets the fake clock run `ms` and the fetch promises settle. */
const tick = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
  await act(async () => {
    await Promise.resolve();
  });
};

describe('shouldPollTaste', () => {
  it.each([
    [undefined, false, false],
    ['FRESH', false, false],
    ['REBUILDING', false, true],
    ['NONE', false, false],
    ['NONE', true, true],
    ['FRESH', true, false],
  ] as const)('%p (pollWhileNone %p) → %p', (status, pollWhileNone, out) => {
    const profile = status ? makeTasteProfile({ status }) : undefined;
    expect(shouldPollTaste(profile, pollWhileNone)).toBe(out);
  });
});

describe('useTasteProfile', () => {
  it('GETs /me/taste-profile', async () => {
    const profile = makeTasteProfile();
    fetchMock.mockResolvedValue(jsonResponse(200, profile));
    const { result } = await renderHookWithClient(() => useTasteProfile());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(urls()).toEqual([`${BASE}/me/taste-profile`]);
    expect(result.current.data).toEqual(profile);
  });

  it('polls every 3s while REBUILDING and stops once FRESH', async () => {
    jest.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, makeTasteProfile({ status: 'REBUILDING' })))
      .mockResolvedValueOnce(jsonResponse(200, makeTasteProfile({ status: 'REBUILDING' })))
      .mockResolvedValue(jsonResponse(200, makeTasteProfile({ status: 'FRESH' })));
    const { result, unmount } = await renderHookWithClient(() => useTasteProfile());
    await waitFor(() => expect(result.current.data?.status).toBe('REBUILDING'));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await tick(TASTE_POLL_MS - 1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await tick(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await tick(TASTE_POLL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await waitFor(() => expect(result.current.data?.status).toBe('FRESH'));

    await tick(TASTE_POLL_MS * 5);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await unmount();
  });

  it('does not poll a FRESH profile', async () => {
    jest.useFakeTimers();
    fetchMock.mockResolvedValue(jsonResponse(200, makeTasteProfile({ status: 'FRESH' })));
    const { result, unmount } = await renderHookWithClient(() => useTasteProfile());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    await tick(TASTE_POLL_MS * 4);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await unmount();
  });

  it('polls a NONE profile only with pollWhileNone', async () => {
    jest.useFakeTimers();
    fetchMock.mockResolvedValue(jsonResponse(200, makeTasteProfile({ status: 'NONE' })));
    const plain = await renderHookWithClient(() => useTasteProfile());
    await waitFor(() => expect(plain.result.current.isSuccess).toBe(true));
    await tick(TASTE_POLL_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await plain.unmount();

    fetchMock.mockClear();
    const polling = await renderHookWithClient(() => useTasteProfile({ pollWhileNone: true }));
    await waitFor(() => expect(polling.result.current.isSuccess).toBe(true));
    await tick(TASTE_POLL_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await polling.unmount();
  });

  it('does nothing while disabled', async () => {
    await renderHookWithClient(() => useTasteProfile({ enabled: false }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('useTasteHistory', () => {
  it('GETs the history, newest first as served', async () => {
    const entries = [makeHistoryEntry({ version: 2 }), makeHistoryEntry({ version: 1 })];
    fetchMock.mockResolvedValue(jsonResponse(200, entries));
    const { result } = await renderHookWithClient(() => useTasteHistory());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(entries);
    expect(urls()).toEqual([`${BASE}/me/taste/history`]);
  });

  it('passes a limit', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, []));
    const { result } = await renderHookWithClient(() => useTasteHistory(5));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(urls()).toEqual([`${BASE}/me/taste/history?limit=5`]);
  });
});

describe('markTasteRebuilding', () => {
  it('flips a cached profile to REBUILDING and invalidates taste queries', () => {
    const client = createTestQueryClient();
    client.setQueryData(queryKeys.tasteProfile, makeTasteProfile({ status: 'FRESH' }));
    client.setQueryData(queryKeys.tasteHistory(null), []);
    markTasteRebuilding(client);
    expect(client.getQueryData<TasteProfileDTO>(queryKeys.tasteProfile)?.status).toBe('REBUILDING');
    expect(client.getQueryState(queryKeys.tasteProfile)?.isInvalidated).toBe(true);
    expect(client.getQueryState(queryKeys.tasteHistory(null))?.isInvalidated).toBe(true);
  });

  it('leaves an empty cache empty', () => {
    const client = createTestQueryClient();
    markTasteRebuilding(client);
    expect(client.getQueryData(queryKeys.tasteProfile)).toBeUndefined();
  });
});
